package com.laserpay.pdei.audit.chain;

import com.laserpay.pdei.audit.config.AuditProperties;
import com.laserpay.pdei.common.event.ActorType;
import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.time.Clocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The property the whole audit service exists to provide: if anyone alters stored history, the
 * chain says so, and says exactly where.
 *
 * <p>Tested against a store double that enforces the real database's constraints - monotonic
 * sequence numbers and one successor per chain link - and that can genuinely mutate a stored row,
 * because a verifier tested only against well-formed input verifies nothing.
 */
class ChainVerifierTest {

    private static final String MERCHANT = "MER-00000001";
    private static final Instant T0 = Instant.parse("2026-08-26T09:00:00Z");

    private InMemoryAuditEventStore store;
    private AuditProperties properties;
    private AuditChainAppender appender;
    private ChainVerifier verifier;

    @BeforeEach
    void setUp() {
        store = new InMemoryAuditEventStore();
        properties = new AuditProperties();
        // No Redis in a unit test: the null lock exercises the path where correctness rests on the
        // unique index rather than on the lock, which is the path that must be correct anyway.
        appender = new AuditChainAppender(store, null, properties, null);
        verifier = new ChainVerifier(store, properties, Clocks.fixed(T0.plus(Duration.ofHours(1))));
    }

    // --- happy path -----------------------------------------------------------------------------

    @Test
    @DisplayName("an untouched chain verifies end to end")
    void intactChainVerifies() {
        appendEntries(5);

        ChainVerificationReport report = verifier.verify(MERCHANT);

        assertThat(report.intact()).isTrue();
        assertThat(report.eventsChecked()).isEqualTo(5);
        assertThat(report.chainLength()).isEqualTo(5);
        assertThat(report.truncated()).isFalse();
        assertThat(report.firstDivergence()).isEmpty();
    }

    @Test
    @DisplayName("an empty chain is intact, not broken")
    void emptyChainIsIntact() {
        ChainVerificationReport report = verifier.verify("MER-EMPTY");

        assertThat(report.intact()).isTrue();
        assertThat(report.eventsChecked()).isZero();
    }

    @Test
    @DisplayName("the first entry of a chain links to the genesis hash")
    void genesisIsTheSixtyFourZeroes() {
        appendEntries(1);

        AuditEvent first = store.findChain(MERCHANT, 10).get(0);
        assertThat(first.previousHash()).isEqualTo(Hashes.GENESIS_HASH);
        assertThat(first.verifyHash()).isTrue();
    }

    @Test
    @DisplayName("each entry links to the hash of the one before it")
    void entriesAreLinked() {
        appendEntries(4);

        List<AuditEvent> chain = store.findChain(MERCHANT, 10);
        for (int i = 1; i < chain.size(); i++) {
            assertThat(chain.get(i).previousHash())
                    .as("entry %d links to entry %d", i, i - 1)
                    .isEqualTo(chain.get(i - 1).hash());
        }
    }

    // --- tampering ------------------------------------------------------------------------------

    @Test
    @DisplayName("editing a stored entry is detected, with the audit id, index and both hashes")
    void detectsATamperedRow() {
        appendEntries(5);
        AuditEvent target = store.findChain(MERCHANT, 10).get(2);
        String originalHash = target.hash();

        // Someone rewrites what an entry says happened, leaving its recorded hash alone.
        store.tamperWith(target.auditId(), "EVIDENCE_APPROVED");

        ChainVerificationReport report = verifier.verify(MERCHANT);

        assertThat(report.intact()).isFalse();
        ChainDivergence divergence = report.firstDivergence().orElseThrow();
        assertThat(divergence.kind()).isEqualTo(ChainDivergence.Kind.TAMPERED_CONTENT);
        assertThat(divergence.auditId()).isEqualTo(target.auditId());
        assertThat(divergence.index()).isEqualTo(2);
        assertThat(divergence.sequenceNo()).isEqualTo(store.sequenceOf(target.auditId()));
        // actual = what the row still claims; expected = what its content now hashes to.
        assertThat(divergence.actualHash()).isEqualTo(originalHash);
        assertThat(divergence.expectedHash()).isNotEqualTo(originalHash);
        assertThat(divergence.detail()).contains("altered");
        // Verification stopped at the first failure rather than reporting the whole tail.
        assertThat(report.eventsChecked()).isEqualTo(3);
    }

    @Test
    @DisplayName("tampering with the very first entry is detected at index 0")
    void detectsTamperingAtTheHeadOfTheChain() {
        appendEntries(3);
        AuditEvent first = store.findChain(MERCHANT, 10).get(0);

        store.tamperWith(first.auditId(), "SOMETHING_ELSE");

        ChainVerificationReport report = verifier.verify(MERCHANT);

        assertThat(report.intact()).isFalse();
        assertThat(report.firstDivergence().orElseThrow().index()).isZero();
        assertThat(report.firstDivergence().orElseThrow().kind())
                .isEqualTo(ChainDivergence.Kind.TAMPERED_CONTENT);
    }

    @Test
    @DisplayName("deleting an entry from the middle breaks the link, not the content")
    void detectsADeletedRow() {
        appendEntries(5);
        AuditEvent removed = store.findChain(MERCHANT, 10).get(2);
        AuditEvent successor = store.findChain(MERCHANT, 10).get(3);

        store.removeAt(2);

        ChainVerificationReport report = verifier.verify(MERCHANT);

        assertThat(report.intact()).isFalse();
        ChainDivergence divergence = report.firstDivergence().orElseThrow();
        assertThat(divergence.kind()).isEqualTo(ChainDivergence.Kind.BROKEN_LINK);
        // The successor is the entry that now fails: it points at a predecessor that is gone.
        assertThat(divergence.auditId()).isEqualTo(successor.auditId());
        assertThat(divergence.actualHash()).isEqualTo(removed.hash());
        assertThat(divergence.detail()).contains("deleted");
    }

    @Test
    @DisplayName("a divergence in one merchant chain does not implicate another")
    void chainsAreIndependentPerMerchant() {
        appendEntries(3);
        appendEntry("MER-00000002", "TRANSACTION", "TX-000000000099", "TRANSACTION_UPDATED", 0);

        AuditEvent target = store.findChain(MERCHANT, 10).get(1);
        store.tamperWith(target.auditId(), "TAMPERED");

        assertThat(verifier.verify(MERCHANT).intact()).isFalse();
        assertThat(verifier.verify("MER-00000002").intact()).isTrue();

        List<ChainVerificationReport> broken = verifier.verifyAll(100);
        assertThat(broken).hasSize(1);
        assertThat(broken.get(0).merchantId()).isEqualTo(MERCHANT);
    }

    // --- truncation -----------------------------------------------------------------------------

    @Test
    @DisplayName("a bounded walk reports itself as truncated rather than as a proof")
    void boundedWalkIsFlaggedTruncated() {
        appendEntries(6);

        ChainVerificationReport report = verifier.verify(MERCHANT, 3);

        assertThat(report.intact()).isTrue();
        assertThat(report.eventsChecked()).isEqualTo(3);
        assertThat(report.chainLength()).isEqualTo(6);
        assertThat(report.truncated())
                .as("intact over a prefix is not intact over the chain")
                .isTrue();
    }

    // --- appender behaviour ---------------------------------------------------------------------

    @Test
    @DisplayName("a redelivered entry is not appended twice")
    void appendIsIdempotentByAuditId() {
        AuditEvent event = unsealed("AUD-0000000000000001", "EVIDENCE", "EV-00000001",
                "EVIDENCE_ADDED", 0);

        appender.append(event);
        appender.append(event);

        assertThat(store.countChain(MERCHANT)).isEqualTo(1);
        assertThat(verifier.verify(MERCHANT).intact()).isTrue();
    }

    @Test
    @DisplayName("an entry sealed against a stale head is re-sealed against the real one")
    void staleProducerSealIsRewritten() {
        appendEntries(2);
        String head = store.lastHash(MERCHANT).orElseThrow();

        // A producer that read the chain head before those two entries existed.
        AuditEvent stale = new AuditEvent("AUD-00000000000000AA", "EVIDENCE", "EV-00000099",
                MERCHANT, "EVIDENCE_INVALIDATED", "state-builder-worker", ActorType.SYSTEM,
                T0.plusSeconds(90), "corr-stale", null, null, Hashes.GENESIS_HASH, null).withHash();

        AuditEvent stored = appender.append(stale);

        assertThat(stored.previousHash()).isEqualTo(head);
        assertThat(stored.hash()).isNotEqualTo(stale.hash());
        // Identity survives re-sealing; only the chain link changes.
        assertThat(stored.auditId()).isEqualTo(stale.auditId());
        assertThat(stored.action()).isEqualTo(stale.action());
        assertThat(verifier.verify(MERCHANT).intact()).isTrue();
    }

    @Test
    @DisplayName("an entry already sealed against the current head keeps the producer's hash")
    void correctlySealedEntryIsStoredVerbatim() {
        appendEntries(1);
        String head = store.lastHash(MERCHANT).orElseThrow();

        AuditEvent presealed = new AuditEvent("AUD-00000000000000BB", "CASE", "CASE-00000001",
                MERCHANT, "CASE_OPENED", "case-orchestrator-service", ActorType.SYSTEM,
                T0.plusSeconds(120), "corr-presealed", null, null, head, null).withHash();

        AuditEvent stored = appender.append(presealed);

        assertThat(stored.hash()).isEqualTo(presealed.hash());
        assertThat(verifier.verify(MERCHANT).intact()).isTrue();
    }

    @Test
    @DisplayName("entries with no merchant go to the platform chain")
    void platformEntriesUseTheirOwnChain() {
        AuditEvent platformEntry = new AuditEvent("AUD-00000000000000CC", "POLICY", "POL-00000001",
                null, "POLICY_PUBLISHED", "api-gateway-service", ActorType.OPERATOR,
                T0, null, null, null, null, null).withHash();

        AuditEvent stored = appender.append(platformEntry);

        assertThat(stored.merchantId()).isEqualTo("PLATFORM");
        assertThat(verifier.verify(null).intact()).isTrue();
        assertThat(store.countChain("PLATFORM")).isEqualTo(1);
    }

    // --- helpers --------------------------------------------------------------------------------

    private void appendEntries(int count) {
        for (int i = 0; i < count; i++) {
            appendEntry(MERCHANT, AggregateType.EVIDENCE.name(), "EV-0000000" + i,
                    "EVIDENCE_ADDED", i);
        }
    }

    private void appendEntry(String merchantId, String entityType, String entityId, String action,
                             int index) {
        appender.append(new AuditEvent(auditId(merchantId, index), entityType, entityId, merchantId,
                action, "state-builder-worker", ActorType.SYSTEM, T0.plusSeconds(index),
                "corr-" + index, null, null, null, null).withHash());
    }

    private static AuditEvent unsealed(String auditId, String entityType, String entityId,
                                       String action, int index) {
        return new AuditEvent(auditId, entityType, entityId, MERCHANT, action,
                "state-builder-worker", ActorType.SYSTEM, T0.plusSeconds(index), "corr-" + index,
                null, null, null, null).withHash();
    }

    private static String auditId(String merchantId, int index) {
        return "AUD-" + Hashes.sha256Hex(merchantId + ":" + index).substring(0, 16).toUpperCase();
    }
}
