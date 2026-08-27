package com.laserpay.pdei.api;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.common.domain.EvidenceSource;
import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.domain.GapType;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.common.domain.RequirementStrength;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.core.model.CaseView;
import com.laserpay.pdei.core.model.CaseXRay;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.ReadinessGap;
import com.laserpay.pdei.core.model.ReadinessSnapshot;
import com.laserpay.pdei.core.model.RequirementView;
import com.laserpay.pdei.core.model.TimelineEntry;
import com.laserpay.pdei.core.model.TransactionFacts;
import com.laserpay.pdei.common.event.AggregateType;
import java.time.Instant;
import java.util.List;

/**
 * Sample domain objects for the controller slice tests, so a test reads as a scenario rather than as
 * a twenty-one-argument constructor call.
 *
 * <p>Every instant is fixed. A test that depends on {@code Instant.now()} passes today and fails on
 * the day the clock crosses whatever boundary it accidentally sat on.</p>
 */
public final class ApiTestFixtures {

    public static final Instant NOW = Instant.parse("2026-08-26T10:15:30Z");

    public static final String MERCHANT_ID = "MER-0001";
    public static final String TRANSACTION_ID = "TX-000123";
    public static final String EVIDENCE_ID = "EV-000999";
    public static final String CASE_ID = "CASE-000042";
    public static final String DISPUTE_ID = "DSP-000042";

    /** INR 12,999.00 in minor units. Never a decimal. */
    public static final Money AMOUNT = Money.of(1_299_900L, "INR");

    private ApiTestFixtures() {
    }

    public static EvidenceView evidence() {
        return evidence(EVIDENCE_ID, EvidenceType.DELIVERY_PROOF, EvidenceStatus.ACTIVE);
    }

    public static EvidenceView evidence(String evidenceId, EvidenceType type, EvidenceStatus status) {
        return new EvidenceView(
                evidenceId,
                MERCHANT_ID,
                TRANSACTION_ID,
                type,
                status,
                EvidenceSource.LOGISTICS,
                MERCHANT_ID + "/" + TRANSACTION_ID + "/" + type + "/" + evidenceId + "/v1/proof.pdf",
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                1,
                "proof.pdf",
                "application/pdf",
                20_480L,
                "Signed delivery proof",
                "11111111-1111-1111-1111-111111111111",
                null,
                "DLV-000001",
                0.95d,
                true,
                NOW.minusSeconds(3600),
                NOW.minusSeconds(3500),
                NOW.plusSeconds(86_400L * 400));
    }

    public static ReadinessSnapshot readiness(int score) {
        return new ReadinessSnapshot(
                "RS-000001",
                TRANSACTION_ID,
                MERCHANT_ID,
                DisputeReasonCode.GOODS_NOT_RECEIVED,
                score,
                ReadinessBand.fromScore(score),
                score,
                0,
                List.of(new RequirementView(EvidenceType.DELIVERY_PROOF, RequirementStrength.MANDATORY,
                        true, List.of(EVIDENCE_ID), 3, null)),
                List.of(gap()),
                List.of(),
                "POLV-000001",
                NOW);
    }

    public static ReadinessGap gap() {
        return new ReadinessGap(
                "GAP-0001",
                TRANSACTION_ID,
                GapType.MISSING,
                EvidenceType.CUSTOMER_COMMUNICATION,
                GapSeverity.MEDIUM,
                null,
                "No customer communication attached",
                NOW,
                null);
    }

    public static TimelineEntry timelineEntry() {
        return TimelineEntry.of(NOW.minusSeconds(7200), "ShipmentDelivered", AggregateType.DELIVERY,
                "DLV-000001", "Delivered and signed for", "LOGISTICS");
    }

    public static TransactionFacts facts() {
        return new TransactionFacts(
                TRANSACTION_ID,
                MERCHANT_ID,
                "CUS-000001",
                AMOUNT,
                "CAPTURED",
                NOW.minusSeconds(86_400),
                List.of(new TransactionFacts.PaymentFact("PAY-000001", "CAPTURED", AMOUNT, "psp-ref-1",
                        NOW.minusSeconds(86_400), NOW.minusSeconds(86_300), NOW.minusSeconds(86_200),
                        "Y", "M")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    public static CaseView caseView(CaseStatus status) {
        return new CaseView(
                CASE_ID,
                DISPUTE_ID,
                MERCHANT_ID,
                TRANSACTION_ID,
                status,
                "case-" + CASE_ID,
                null,
                1,
                NOW.minusSeconds(7200),
                NOW,
                null);
    }

    public static CaseXRay xray() {
        return new CaseXRay(
                CASE_ID,
                DISPUTE_ID,
                TRANSACTION_ID,
                MERCHANT_ID,
                CaseStatus.AWAITING_APPROVAL,
                DisputeStatus.AWAITING_HUMAN_REVIEW,
                DisputeReasonCode.GOODS_NOT_RECEIVED,
                AMOUNT,
                NOW.plusSeconds(86_400L * 14),
                readiness(88),
                List.of(evidence()),
                null,
                List.of(timelineEntry()),
                List.of(gap()),
                List.of(),
                null,
                null,
                null,
                List.of("AUD-000001"),
                NOW);
    }
}
