package com.laserpay.pdei.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laserpay.pdei.common.domain.EvidenceSource;
import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.persistence.AbstractPostgresIntegrationTest;
import com.laserpay.pdei.persistence.entity.EvidenceEntity;
import com.laserpay.pdei.persistence.entity.EvidenceVersionEntity;
import com.laserpay.pdei.persistence.entity.MerchantEntity;
import com.laserpay.pdei.persistence.entity.MoneyEmbeddable;
import com.laserpay.pdei.persistence.entity.TransactionEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * Mapping-level proof that the entities and the migrated schema agree: money round-trips as
 * integer minor units, JSON columns survive, the FTS trigger populates {@code search_vector},
 * and version history really is append-only.
 */
@EnabledIf(value = "com.laserpay.pdei.persistence.AbstractPostgresIntegrationTest#dockerAvailable",
        disabledReason = "Docker is not available; skipping Testcontainers integration test")
class EvidencePersistenceIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String MERCHANT_ID = "MER-TEST0001";
    private static final String TRANSACTION_ID = "TX-TEST0001";

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private EvidenceRepository evidence;

    @Autowired
    private EvidenceVersionRepository evidenceVersions;

    @BeforeEach
    void seedMerchantAndTransaction() {
        MerchantEntity merchant = new MerchantEntity();
        merchant.setId(MERCHANT_ID);
        merchant.setLegalName("Test Retail Private Limited");
        merchant.setDisplayName("Test Retail");
        merchant.setCountry("IN");
        merchant.setDefaultCurrency("INR");
        merchant.setBaselineWinRateBps(7100);
        merchant.setRiskProfile(Map.of("tier", "STANDARD", "reviewRequired", false));
        merchants.save(merchant);

        TransactionEntity transaction = new TransactionEntity();
        transaction.setId(TRANSACTION_ID);
        transaction.setMerchantId(MERCHANT_ID);
        transaction.setStatus("CAPTURED");
        transaction.setChannel("ONLINE");
        transaction.setOccurredAt(Instant.parse("2026-08-01T10:15:30Z"));
        transaction.setObservedAt(Instant.parse("2026-08-01T10:15:31Z"));
        transaction.setAmountFromMoney(Money.of(1_299_900L, "INR"));
        transaction.setCapturedAmountFromMoney(Money.of(1_299_900L, "INR"));
        transaction.setRefundedAmountFromMoney(Money.zero("INR"));
        transaction.setReadinessScore(92);
        transaction.setReadinessBand(ReadinessBand.READY);
        transaction.setMetadata(Map.of("source", "SIMULATOR"));
        transactions.save(transaction);
    }

    @Test
    @DisplayName("money round-trips as (amount_minor, currency) with no precision loss")
    void moneyRoundTrips() {
        TransactionEntity reloaded = transactions.findById(TRANSACTION_ID).orElseThrow();

        assertThat(reloaded.getAmount().getAmountMinor()).isEqualTo(1_299_900L);
        assertThat(reloaded.getAmount().getCurrency()).isEqualTo("INR");
        assertThat(reloaded.getAmountAsMoney()).isEqualTo(Money.of(1_299_900L, "INR"));
        assertThat(reloaded.getRefundedAmountAsMoney().isZero()).isTrue();
        assertThat(reloaded.getOccurredAt()).isEqualTo(Instant.parse("2026-08-01T10:15:30Z"));
        assertThat(reloaded.getReadinessBand()).isEqualTo(ReadinessBand.READY);
        assertThat(reloaded.getMetadata()).containsEntry("source", "SIMULATOR");

        // the physical columns are what the contract mandates, not a serialized object
        Long amountMinor = jdbcTemplate.queryForObject(
                "SELECT amount_minor FROM pdei.transactions WHERE transaction_id = ?",
                Long.class, TRANSACTION_ID);
        String currency = jdbcTemplate.queryForObject(
                "SELECT currency FROM pdei.transactions WHERE transaction_id = ?",
                String.class, TRANSACTION_ID);
        assertThat(amountMinor).isEqualTo(1_299_900L);
        assertThat(currency).isEqualTo("INR");
    }

    @Test
    @DisplayName("evidence full-text search ranks on the trigger-maintained tsvector")
    void evidenceFullTextSearch() {
        evidence.save(item("EV-TEST0001", EvidenceType.DELIVERY_PROOF, "abc123",
                "Delivery proof for order 99172",
                "Signed courier delivery confirmation for the disputed shipment."));
        evidence.save(item("EV-TEST0002", EvidenceType.REFUND_RECEIPT, "def456",
                "Refund receipt",
                "Full refund issued to the original payment method."));

        List<EvidenceEntity> hits = evidence.search("delivery", MERCHANT_ID, PageRequest.of(0, 10));
        assertThat(hits).extracting(EvidenceEntity::getId).containsExactly("EV-TEST0001");

        List<EvidenceEntity> phrase = evidence.search("\"courier delivery\"", MERCHANT_ID, PageRequest.of(0, 10));
        assertThat(phrase).hasSize(1);

        List<EvidenceEntity> negated = evidence.search("refund -receipt", MERCHANT_ID, PageRequest.of(0, 10));
        assertThat(negated).isEmpty();

        // a null query degrades to "latest evidence for this merchant" rather than failing
        assertThat(evidence.search(null, MERCHANT_ID, PageRequest.of(0, 10))).hasSize(2);
    }

    @Test
    @DisplayName("content hash is unique per transaction and looked up by findByShaAndTransactionId")
    void contentHashLookup() {
        evidence.save(item("EV-TEST0003", EvidenceType.DELIVERY_PROOF, "hash-of-bytes",
                "Delivery proof", "Proof body"));

        assertThat(evidence.findByShaAndTransactionId("hash-of-bytes", TRANSACTION_ID))
                .isPresent()
                .get()
                .extracting(EvidenceEntity::getId)
                .isEqualTo("EV-TEST0003");
        assertThat(evidence.findByShaAndTransactionId("unknown", TRANSACTION_ID)).isEmpty();
        assertThat(evidence.findByTransactionIdAndStatusIn(TRANSACTION_ID, List.of(EvidenceStatus.ACTIVE)))
                .hasSize(1);
        assertThat(evidence.findByMerchantIdAndTypeAndStatus(
                MERCHANT_ID, EvidenceType.DELIVERY_PROOF, EvidenceStatus.ACTIVE)).hasSize(1);
    }

    @Test
    @DisplayName("evidence version history is append-only and preserves the parent chain")
    void versionHistoryIsAppendOnly() {
        evidence.save(item("EV-TEST0004", EvidenceType.DELIVERY_PROOF, "v1hash",
                "Delivery proof", "First scan"));

        evidenceVersions.save(version("EV-TEST0004", 1, null, "v1hash"));
        evidenceVersions.save(version("EV-TEST0004", 2, 1, "v2hash"));

        List<EvidenceVersionEntity> chain =
                evidenceVersions.findByEvidenceIdOrderByVersionNumberAsc("EV-TEST0004");
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).getParentVersion()).isNull();
        assertThat(chain.get(1).getParentVersion()).isEqualTo(1);
        assertThat(evidenceVersions.findTopByEvidenceIdOrderByVersionNumberDesc("EV-TEST0004"))
                .get()
                .extracting(EvidenceVersionEntity::getSha256)
                .isEqualTo("v2hash");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE pdei.evidence_versions SET sha256 = 'tampered' WHERE evidence_version_id = ?",
                "EV-TEST0004-v1"))
                .as("the database itself must refuse to rewrite history")
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM pdei.evidence_versions WHERE evidence_version_id = ?",
                "EV-TEST0004-v1"))
                .hasMessageContaining("append-only");
    }

    private EvidenceEntity item(String id, EvidenceType type, String sha256, String title, String summary) {
        EvidenceEntity item = new EvidenceEntity();
        item.setId(id);
        item.setMerchantId(MERCHANT_ID);
        item.setTransactionId(TRANSACTION_ID);
        item.setType(type);
        item.setStatus(EvidenceStatus.ACTIVE);
        item.setSource(EvidenceSource.LOGISTICS);
        item.setObservedAt(Instant.parse("2026-08-02T09:00:00Z"));
        item.setCapturedAt(Instant.parse("2026-08-02T08:55:00Z"));
        item.setSha256(sha256);
        item.setFilename("delivery-proof.pdf");
        item.setContentType("application/pdf");
        item.setTitle(title);
        item.setSummary(summary);
        item.setAmount(MoneyEmbeddable.of(1_299_900L, "INR"));
        item.setProvenance(Map.of("carrier", "BlueDart", "sourceEventId", "evt-1"));
        return item;
    }

    private EvidenceVersionEntity version(String evidenceId, int versionNumber, Integer parent, String sha256) {
        EvidenceVersionEntity version = new EvidenceVersionEntity();
        version.setId(EvidenceVersionEntity.idFor(evidenceId, versionNumber));
        version.setEvidenceId(evidenceId);
        version.setVersionNumber(versionNumber);
        version.setParentVersion(parent);
        version.setSha256(sha256);
        version.setObjectKey(MERCHANT_ID + "/" + TRANSACTION_ID + "/DELIVERY_PROOF/" + evidenceId
                + "/v" + versionNumber + "/delivery-proof.pdf");
        version.setStatus(EvidenceStatus.ACTIVE);
        version.setSource(EvidenceSource.LOGISTICS);
        version.setObservedAt(Instant.parse("2026-08-02T09:00:00Z"));
        return version;
    }
}
