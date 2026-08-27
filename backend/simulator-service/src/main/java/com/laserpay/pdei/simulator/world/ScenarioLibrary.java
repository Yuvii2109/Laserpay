package com.laserpay.pdei.simulator.world;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.GapType;
import com.laserpay.pdei.common.domain.InvestigationClassification;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.error.NotFoundException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The curated scenarios behind {@code GET /sim/v1/scenarios} and
 * {@code POST /sim/v1/scenarios/{key}/run}.
 *
 * <h2>Why the expectations are in the code</h2>
 * A demo that produces "some data" proves nothing. Each scenario here pins a seed, a failure mix
 * and a dispute reason code, and then <em>declares the outcome</em>: which readiness band, which
 * gap types, and - the claim the whole architecture rests on - whether the case should be
 * resolved deterministically or sent to the model. That makes each one an executable assertion
 * about the deterministic engine rather than a bag of fixtures.
 *
 * <h2>Where the numbers come from</h2>
 * The expected score ranges are derived from the scoring formula in platform contract 7 against
 * the requirement matrix in {@code DefaultPolicyMatrix}. For {@code GOODS_NOT_RECEIVED} the
 * denominator is {@code 5 mandatory x 3 + 0.5 x (2 recommended x 2) = 17}; dropping
 * DELIVERY_PROOF removes 3 from the numerator, an expired MERCHANT_POLICY removes 3 and adds a
 * -10 penalty, and each contradiction costs a flat -15. The ranges are stated with a few points
 * of slack because scoring is exercised end to end by readiness-worker, not by this module.
 *
 * <h2>Determinism</h2>
 * Every scenario fixes {@code startAt} at {@link WorldSpec#DEFAULT_START_AT}, so running the same
 * key twice produces byte-identical events. To get recent-looking timestamps for a live demo,
 * override {@code startAt} on the run request - determinism holds for any fixed value.
 */
public final class ScenarioLibrary {

    public static final String CLEAN_DELIVERY_DEFENDABLE = "clean-delivery-defendable";
    public static final String MISSING_DELIVERY_PROOF = "missing-delivery-proof";
    public static final String CONTRADICTORY_DELIVERY_DATES = "contradictory-delivery-dates";
    public static final String EXPIRED_POLICY_EVIDENCE = "expired-policy-evidence";
    public static final String DUPLICATE_CHARGE_DISPUTE = "duplicate-charge-dispute";
    public static final String PARTIAL_REFUND_DISPUTE = "partial-refund-dispute";
    public static final String MULTI_SHIPMENT_ORDER = "multi-shipment-order";
    public static final String LATE_EVIDENCE_ARRIVAL = "late-evidence-arrival";
    public static final String SUBSCRIPTION_CANCELLED_DISPUTE = "subscription-cancelled-dispute";
    public static final String HIGH_VALUE_URGENT_DEADLINE = "high-value-urgent-deadline";

    /** 12,999.00 INR floor for the high-value scenario, in minor units. */
    private static final long HIGH_VALUE_FLOOR_MINOR = 1_299_900L;

    private static final Map<String, Scenario> SCENARIOS = build();

    private ScenarioLibrary() {
    }

    /** Every scenario, in demo order. */
    public static List<Scenario> all() {
        return List.copyOf(SCENARIOS.values());
    }

    public static Optional<Scenario> find(String key) {
        return Optional.ofNullable(key == null ? null : SCENARIOS.get(key.strip()));
    }

    /** @throws NotFoundException when no scenario has that key */
    public static Scenario require(String key) {
        return find(key).orElseThrow(() -> new NotFoundException("scenario", String.valueOf(key)));
    }

    public static List<String> keys() {
        return List.copyOf(SCENARIOS.keySet());
    }

    private static Map<String, Scenario> build() {
        Map<String, Scenario> scenarios = new LinkedHashMap<>();

        // -----------------------------------------------------------------------------------
        // 1. The happy path. Everything present, everything consistent.
        // -----------------------------------------------------------------------------------
        add(scenarios, new Scenario(
                CLEAN_DELIVERY_DEFENDABLE,
                "Clean delivery, fully defendable",
                "Every mandatory and recommended artifact is present and internally consistent: "
                        + "payment proof, invoice, order record, shipping record, signed delivery "
                        + "proof, a live merchant policy and a customer email acknowledging receipt.",
                DisputeReasonCode.GOODS_NOT_RECEIVED,
                WorldSpec.scenario(4281L, CLEAN_DELIVERY_DEFENDABLE, 8, 21,
                        FailureMix.clean().withCustomerContact(FailureMix.FULL_BPS).withRefunds(0),
                        DisputeReasonCode.GOODS_NOT_RECEIVED),
                ReadinessBand.READY, 95, 100,
                List.of(),
                AiPath.DETERMINISTIC,
                InvestigationClassification.DEFENDABLE,
                RecommendedAction.PREPARE_REPRESENTMENT,
                "The baseline. Zero AI calls: all MANDATORY satisfied and zero contradictions is a "
                        + "deterministic short-circuit straight to PREPARE_REPRESENTMENT."));

        // -----------------------------------------------------------------------------------
        // 2. The single most common real loss: delivered, but nobody kept the proof.
        // -----------------------------------------------------------------------------------
        add(scenarios, new Scenario(
                MISSING_DELIVERY_PROOF,
                "Delivered, but no proof of delivery",
                "The carrier reports delivery and the shipping record exists, but the signed proof "
                        + "of delivery was never uploaded. The MANDATORY DELIVERY_PROOF requirement "
                        + "is unsatisfied.",
                DisputeReasonCode.GOODS_NOT_RECEIVED,
                WorldSpec.scenario(9137L, MISSING_DELIVERY_PROOF, 8, 21,
                        FailureMix.clean()
                                .withMissingDeliveryProof(FailureMix.FULL_BPS)
                                .withCustomerContact(FailureMix.FULL_BPS)
                                .withRefunds(0),
                        DisputeReasonCode.GOODS_NOT_RECEIVED),
                ReadinessBand.NEARLY_READY, 78, 86,
                List.of(GapType.MISSING),
                AiPath.AMBIGUOUS,
                InvestigationClassification.INSUFFICIENT_EVIDENCE,
                RecommendedAction.GATHER_MORE_EVIDENCE,
                "The gap is detected BEFORE a dispute exists - this is the product's whole "
                        + "pre-dispute premise. An unsatisfied MANDATORY requirement blocks the "
                        + "deterministic short-circuit, so the case reaches admission control."));

        // -----------------------------------------------------------------------------------
        // 3. Two delivery records that cannot both be true.
        // -----------------------------------------------------------------------------------
        add(scenarios, new Scenario(
                CONTRADICTORY_DELIVERY_DATES,
                "Contradictory delivery dates",
                "A second delivery record from the merchant portal is dated six hours BEFORE the "
                        + "carrier dispatched the parcel. The two cannot both be true, and the "
                        + "impossible ordering is machine-detectable.",
                DisputeReasonCode.GOODS_NOT_RECEIVED,
                WorldSpec.scenario(5507L, CONTRADICTORY_DELIVERY_DATES, 8, 21,
                        FailureMix.clean()
                                .withContradictoryDelivery(FailureMix.FULL_BPS)
                                .withCustomerContact(FailureMix.FULL_BPS)
                                .withRefunds(0),
                        DisputeReasonCode.GOODS_NOT_RECEIVED),
                ReadinessBand.NEARLY_READY, 80, 89,
                List.of(GapType.CONTRADICTORY),
                AiPath.AMBIGUOUS,
                InvestigationClassification.AMBIGUOUS,
                RecommendedAction.ESCALATE_TO_HUMAN,
                "Every artifact is present, so a naive completeness check would call this ready. "
                        + "ContradictionDetector finds deliveredAt < dispatchedAt: -15, and "
                        + "maxContradictions = 0 means the safety gate will not auto-prepare."));

        // -----------------------------------------------------------------------------------
        // 4. The document exists, but it expired.
        // -----------------------------------------------------------------------------------
        add(scenarios, new Scenario(
                EXPIRED_POLICY_EVIDENCE,
                "Expired policy evidence",
                "The merchant's refund policy and terms of service expired five days before the "
                        + "world begins. MERCHANT_POLICY is MANDATORY for this reason code, so an "
                        + "expired document is not a usable one.",
                DisputeReasonCode.GOODS_NOT_RECEIVED,
                WorldSpec.scenario(7724L, EXPIRED_POLICY_EVIDENCE, 8, 21,
                        FailureMix.clean()
                                .withExpiredEvidence(FailureMix.FULL_BPS)
                                .withCustomerContact(FailureMix.FULL_BPS)
                                .withRefunds(0),
                        DisputeReasonCode.GOODS_NOT_RECEIVED),
                ReadinessBand.AT_RISK, 66, 74,
                List.of(GapType.EXPIRED),
                AiPath.AMBIGUOUS,
                InvestigationClassification.WEAK,
                RecommendedAction.REQUEST_POLICY_REVIEW,
                "Present-but-expired is the failure mode a file-count dashboard cannot see: the "
                        + "requirement is unsatisfied AND a -10 expiry penalty applies."));

        // -----------------------------------------------------------------------------------
        // 5. Idempotency, demonstrated: every single event delivered twice.
        // -----------------------------------------------------------------------------------
        add(scenarios, new Scenario(
                DUPLICATE_CHARGE_DISPUTE,
                "Duplicate charge claim with duplicated events",
                "The customer claims they were charged twice. Every generated event is also "
                        + "emitted twice with an identical idempotency key, so the platform must "
                        + "produce exactly the same state as a single-delivery run.",
                DisputeReasonCode.DUPLICATE_PROCESSING,
                WorldSpec.scenario(3312L, DUPLICATE_CHARGE_DISPUTE, 8, 21,
                        FailureMix.clean()
                                .withDuplicateEvents(FailureMix.FULL_BPS)
                                .withCustomerContact(FailureMix.FULL_BPS)
                                .withRefunds(0),
                        DisputeReasonCode.DUPLICATE_PROCESSING),
                ReadinessBand.READY, 90, 95,
                List.of(),
                AiPath.DETERMINISTIC,
                InvestigationClassification.DEFENDABLE,
                RecommendedAction.PREPARE_REPRESENTMENT,
                "Run it, then run it again: event counts double, readiness does not move. That is "
                        + "the idempotency claim (rule 9) made visible. REFUND_RECEIPT is "
                        + "RECOMMENDED and absent, which is why the score sits below 100."));

        // -----------------------------------------------------------------------------------
        // 6. Money arithmetic in minor units, on a partial credit.
        // -----------------------------------------------------------------------------------
        add(scenarios, new Scenario(
                PARTIAL_REFUND_DISPUTE,
                "Partial refund, credit-not-processed claim",
                "A partial refund of 30-70% was processed and receipted. The customer disputes the "
                        + "remainder, so the dispute amount is the original total minus the credit - "
                        + "computed in minor units, never in decimals.",
                DisputeReasonCode.CREDIT_NOT_PROCESSED,
                WorldSpec.scenario(6180L, PARTIAL_REFUND_DISPUTE, 8, 28,
                        FailureMix.clean()
                                .withRefunds(FailureMix.FULL_BPS)
                                .withPartialRefunds(FailureMix.FULL_BPS)
                                .withCustomerContact(FailureMix.FULL_BPS),
                        DisputeReasonCode.CREDIT_NOT_PROCESSED),
                ReadinessBand.READY, 92, 100,
                List.of(),
                AiPath.DETERMINISTIC,
                InvestigationClassification.DEFENDABLE,
                RecommendedAction.PREPARE_REPRESENTMENT,
                "All three MANDATORY artifacts for CREDIT_NOT_PROCESSED are present, so readiness "
                        + "resolves deterministically. The interesting part is downstream: the "
                        + "representment narrative has to reconcile refunded vs disputed minor units."));

        // -----------------------------------------------------------------------------------
        // 7. A split order where one parcel never arrives.
        // -----------------------------------------------------------------------------------
        add(scenarios, new Scenario(
                MULTI_SHIPMENT_ORDER,
                "Multi-shipment order with one parcel in transit",
                "The order ships in two or three parcels. One is still in transit when the dispute "
                        + "is opened, so there is a delivery proof for some items but not all of "
                        + "them.",
                DisputeReasonCode.GOODS_NOT_RECEIVED,
                WorldSpec.scenario(8846L, MULTI_SHIPMENT_ORDER, 8, 28,
                        FailureMix.clean()
                                .withMultiShipment(FailureMix.FULL_BPS)
                                .withCustomerContact(FailureMix.FULL_BPS)
                                .withRefunds(0),
                        DisputeReasonCode.GOODS_NOT_RECEIVED),
                ReadinessBand.READY, 92, 100,
                List.of(),
                AiPath.DETERMINISTIC,
                InvestigationClassification.DEFENDABLE,
                RecommendedAction.PREPARE_REPRESENTMENT,
                "Deliberately shows a LIMITATION: requirements are checked per evidence TYPE, so "
                        + "one delivery proof satisfies DELIVERY_PROOF even though a parcel is "
                        + "still moving. Per-line-item coverage is a known gap, and this is where "
                        + "to demonstrate it honestly."));

        // -----------------------------------------------------------------------------------
        // 8. The proof arrives after the dispute does.
        // -----------------------------------------------------------------------------------
        add(scenarios, new Scenario(
                LATE_EVIDENCE_ARRIVAL,
                "Delivery proof arrives after the dispute",
                "The parcel is delivered on time, but the signed proof is not uploaded for sixty "
                        + "days - well after the dispute is opened. Readiness must recompute "
                        + "upward when the late artifact finally lands.",
                DisputeReasonCode.GOODS_NOT_RECEIVED,
                WorldSpec.scenario(2059L, LATE_EVIDENCE_ARRIVAL, 8, 28,
                        FailureMix.clean()
                                .withDeliveryProofLateDays(60)
                                .withCustomerContact(FailureMix.FULL_BPS)
                                .withRefunds(0),
                        DisputeReasonCode.GOODS_NOT_RECEIVED),
                ReadinessBand.READY, 95, 100,
                List.of(),
                AiPath.DETERMINISTIC,
                InvestigationClassification.DEFENDABLE,
                RecommendedAction.PREPARE_REPRESENTMENT,
                "Watch the readiness score, not the end state. It starts in the MISSING-gap band "
                        + "and climbs to READY when the sixty-day-late proof is observed. Rule 10 "
                        + "(assume late and out-of-order events) demonstrated end to end."));

        // -----------------------------------------------------------------------------------
        // 9. A subscription dispute where the governing document has lapsed.
        // -----------------------------------------------------------------------------------
        add(scenarios, new Scenario(
                SUBSCRIPTION_CANCELLED_DISPUTE,
                "Subscription cancelled, terms of service expired",
                "The customer says they cancelled before the renewal. The cancellation email "
                        + "exists, but the terms of service the merchant would rely on - and the "
                        + "refund policy - both expired. Two of the four MANDATORY artifacts for "
                        + "this reason code are unusable.",
                DisputeReasonCode.SUBSCRIPTION_CANCELLED,
                WorldSpec.scenario(4472L, SUBSCRIPTION_CANCELLED_DISPUTE, 8, 28,
                        FailureMix.clean()
                                .withExpiredEvidence(FailureMix.FULL_BPS)
                                .withCustomerContact(FailureMix.FULL_BPS)
                                .withRefunds(0),
                        DisputeReasonCode.SUBSCRIPTION_CANCELLED),
                ReadinessBand.NOT_READY, 22, 40,
                List.of(GapType.EXPIRED, GapType.MISSING),
                AiPath.AMBIGUOUS,
                InvestigationClassification.WEAK,
                RecommendedAction.ESCALATE_TO_HUMAN,
                "SUBSCRIPTION_CANCELLED needs TERMS_OF_SERVICE and CUSTOMER_COMMUNICATION as "
                        + "MANDATORY. Two expired mandatory documents cost both their weight and "
                        + "two -10 penalties; SIGNED_CONTRACT is never generated, so a MISSING gap "
                        + "appears on the RECOMMENDED side too."));

        // -----------------------------------------------------------------------------------
        // 10. Same gap as scenario 2, but this one must jump the queue.
        // -----------------------------------------------------------------------------------
        add(scenarios, new Scenario(
                HIGH_VALUE_URGENT_DEADLINE,
                "High value, deadline inside 48 hours",
                "A 12,999.00 INR floor on every transaction and a representment deadline one day "
                        + "after the dispute opens. The evidence gap is identical to "
                        + "missing-delivery-proof; the difference is entirely in the priority.",
                DisputeReasonCode.GOODS_NOT_RECEIVED,
                WorldSpec.scenario(1174L, HIGH_VALUE_URGENT_DEADLINE, 6, 14,
                                FailureMix.clean()
                                        .withMissingDeliveryProof(FailureMix.FULL_BPS)
                                        .withCustomerContact(FailureMix.FULL_BPS)
                                        .withRefunds(0),
                                DisputeReasonCode.GOODS_NOT_RECEIVED)
                        .withMinAmountMinor(HIGH_VALUE_FLOOR_MINOR)
                        .withDisputeDeadlineDays(1),
                ReadinessBand.NEARLY_READY, 78, 86,
                List.of(GapType.MISSING),
                AiPath.AMBIGUOUS,
                InvestigationClassification.INSUFFICIENT_EVIDENCE,
                RecommendedAction.ESCALATE_TO_HUMAN,
                "Run this next to missing-delivery-proof: same readiness, same gap, much higher "
                        + "admission priority (0.40 x financial impact + 0.25 x deadline urgency, "
                        + "which is 1.0 inside 48 hours). Override startAt on the run request so "
                        + "the deadline is genuinely ahead of now, otherwise the past-deadline "
                        + "short-circuit escalates it without scoring."));

        return scenarios;
    }

    private static void add(Map<String, Scenario> scenarios, Scenario scenario) {
        if (scenarios.put(scenario.key(), scenario) != null) {
            throw new IllegalStateException("duplicate scenario key: " + scenario.key());
        }
    }
}
