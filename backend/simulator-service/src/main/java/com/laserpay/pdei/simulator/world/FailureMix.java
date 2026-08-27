package com.laserpay.pdei.simulator.world;

/**
 * How broken the generated world is, expressed almost entirely in basis points.
 *
 * <p>Every rate is an integer bps value (10000 = 100%). Rates are never floats here for the same
 * reason money never is: a {@code double} rate would make "deterministic given a seed" depend on
 * floating-point rounding, and the whole point of this generator is that seed 4281 produces
 * byte-identical events on every machine that runs it.
 *
 * <p>These knobs describe the world <em>as generated</em>. They are not the chaos engine: chaos
 * is injected into a running stream after the fact
 * ({@link com.laserpay.pdei.simulator.chaos.ChaosEngine}), while these produce the
 * messy-but-normal data a real merchant actually has - a delivery proof nobody uploaded, a policy
 * PDF that expired last quarter, an order split across three parcels where one is still moving.
 *
 * @param missingDeliveryProofBps  transactions whose DELIVERY_PROOF evidence is never emitted
 * @param contradictoryDeliveryBps transactions with a second delivery record dated before dispatch
 * @param expiredEvidenceBps       merchants whose MERCHANT_POLICY / TERMS_OF_SERVICE has expired
 * @param duplicateEventBps        events emitted twice with the same idempotency key
 * @param lateEventBps             events whose observedAt trails occurredAt by days
 * @param outOfOrderBps            adjacent events emitted in the wrong order
 * @param droppedEventBps          evidence or communication events generated then not emitted
 * @param partialRefundBps         refunds that cover only part of the transaction
 * @param multiShipmentBps         orders split across several shipments
 * @param paymentFailureBps        payments that fail instead of capturing
 * @param customerContactBps       transactions with an inbound customer communication
 * @param refundBps                transactions that end in a refund
 * @param deliveryProofLateDays    forced lag between a delivery happening and its proof being
 *                                 observed; 0 means normal timing. Large values push the proof
 *                                 past the dispute, which is what proves late-event tolerance.
 */
public record FailureMix(int missingDeliveryProofBps,
                         int contradictoryDeliveryBps,
                         int expiredEvidenceBps,
                         int duplicateEventBps,
                         int lateEventBps,
                         int outOfOrderBps,
                         int droppedEventBps,
                         int partialRefundBps,
                         int multiShipmentBps,
                         int paymentFailureBps,
                         int customerContactBps,
                         int refundBps,
                         int deliveryProofLateDays) {

    public static final int FULL_BPS = 10_000;
    private static final int MAX_LATE_DAYS = 365;

    public FailureMix {
        missingDeliveryProofBps = clamp(missingDeliveryProofBps);
        contradictoryDeliveryBps = clamp(contradictoryDeliveryBps);
        expiredEvidenceBps = clamp(expiredEvidenceBps);
        duplicateEventBps = clamp(duplicateEventBps);
        lateEventBps = clamp(lateEventBps);
        outOfOrderBps = clamp(outOfOrderBps);
        droppedEventBps = clamp(droppedEventBps);
        partialRefundBps = clamp(partialRefundBps);
        multiShipmentBps = clamp(multiShipmentBps);
        paymentFailureBps = clamp(paymentFailureBps);
        customerContactBps = clamp(customerContactBps);
        refundBps = clamp(refundBps);
        deliveryProofLateDays = Math.max(0, Math.min(MAX_LATE_DAYS, deliveryProofLateDays));
    }

    /** Nothing goes wrong. Every transaction is fully evidenced and internally consistent. */
    public static FailureMix clean() {
        return new FailureMix(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3500, 500, 0);
    }

    /**
     * What a competent merchant's data actually looks like: mostly fine, with the handful of gaps
     * that turn out to matter only once a dispute arrives.
     */
    public static FailureMix realistic() {
        return new FailureMix(800, 400, 600, 300, 700, 400, 100, 1200, 1500, 400, 4000, 1500, 0);
    }

    /** Stress shape for benchmarks: every failure mode at once. */
    public static FailureMix hostile() {
        return new FailureMix(2500, 1800, 2000, 1200, 2200, 1500, 500, 2500, 3000, 1200, 5000, 2500, 0);
    }

    public static FailureMix of(FailureProfile profile) {
        return switch (profile == null ? FailureProfile.REALISTIC : profile) {
            case CLEAN -> clean();
            case REALISTIC -> realistic();
            case HOSTILE -> hostile();
        };
    }

    // -------------------------------------------------------------------------------------
    // Withers used by the curated scenarios to pin one failure mode to certainty.
    // -------------------------------------------------------------------------------------

    public FailureMix withMissingDeliveryProof(int bps) {
        return new FailureMix(bps, contradictoryDeliveryBps, expiredEvidenceBps, duplicateEventBps,
                lateEventBps, outOfOrderBps, droppedEventBps, partialRefundBps, multiShipmentBps,
                paymentFailureBps, customerContactBps, refundBps, deliveryProofLateDays);
    }

    public FailureMix withContradictoryDelivery(int bps) {
        return new FailureMix(missingDeliveryProofBps, bps, expiredEvidenceBps, duplicateEventBps,
                lateEventBps, outOfOrderBps, droppedEventBps, partialRefundBps, multiShipmentBps,
                paymentFailureBps, customerContactBps, refundBps, deliveryProofLateDays);
    }

    public FailureMix withExpiredEvidence(int bps) {
        return new FailureMix(missingDeliveryProofBps, contradictoryDeliveryBps, bps,
                duplicateEventBps, lateEventBps, outOfOrderBps, droppedEventBps, partialRefundBps,
                multiShipmentBps, paymentFailureBps, customerContactBps, refundBps,
                deliveryProofLateDays);
    }

    public FailureMix withDuplicateEvents(int bps) {
        return new FailureMix(missingDeliveryProofBps, contradictoryDeliveryBps, expiredEvidenceBps,
                bps, lateEventBps, outOfOrderBps, droppedEventBps, partialRefundBps,
                multiShipmentBps, paymentFailureBps, customerContactBps, refundBps,
                deliveryProofLateDays);
    }

    public FailureMix withLateEvents(int bps) {
        return new FailureMix(missingDeliveryProofBps, contradictoryDeliveryBps, expiredEvidenceBps,
                duplicateEventBps, bps, outOfOrderBps, droppedEventBps, partialRefundBps,
                multiShipmentBps, paymentFailureBps, customerContactBps, refundBps,
                deliveryProofLateDays);
    }

    public FailureMix withOutOfOrder(int bps) {
        return new FailureMix(missingDeliveryProofBps, contradictoryDeliveryBps, expiredEvidenceBps,
                duplicateEventBps, lateEventBps, bps, droppedEventBps, partialRefundBps,
                multiShipmentBps, paymentFailureBps, customerContactBps, refundBps,
                deliveryProofLateDays);
    }

    public FailureMix withPartialRefunds(int bps) {
        return new FailureMix(missingDeliveryProofBps, contradictoryDeliveryBps, expiredEvidenceBps,
                duplicateEventBps, lateEventBps, outOfOrderBps, droppedEventBps, bps,
                multiShipmentBps, paymentFailureBps, customerContactBps, refundBps,
                deliveryProofLateDays);
    }

    public FailureMix withMultiShipment(int bps) {
        return new FailureMix(missingDeliveryProofBps, contradictoryDeliveryBps, expiredEvidenceBps,
                duplicateEventBps, lateEventBps, outOfOrderBps, droppedEventBps, partialRefundBps,
                bps, paymentFailureBps, customerContactBps, refundBps, deliveryProofLateDays);
    }

    public FailureMix withPaymentFailures(int bps) {
        return new FailureMix(missingDeliveryProofBps, contradictoryDeliveryBps, expiredEvidenceBps,
                duplicateEventBps, lateEventBps, outOfOrderBps, droppedEventBps, partialRefundBps,
                multiShipmentBps, bps, customerContactBps, refundBps, deliveryProofLateDays);
    }

    public FailureMix withCustomerContact(int bps) {
        return new FailureMix(missingDeliveryProofBps, contradictoryDeliveryBps, expiredEvidenceBps,
                duplicateEventBps, lateEventBps, outOfOrderBps, droppedEventBps, partialRefundBps,
                multiShipmentBps, paymentFailureBps, bps, refundBps, deliveryProofLateDays);
    }

    public FailureMix withRefunds(int bps) {
        return new FailureMix(missingDeliveryProofBps, contradictoryDeliveryBps, expiredEvidenceBps,
                duplicateEventBps, lateEventBps, outOfOrderBps, droppedEventBps, partialRefundBps,
                multiShipmentBps, paymentFailureBps, customerContactBps, bps, deliveryProofLateDays);
    }

    public FailureMix withDeliveryProofLateDays(int days) {
        return new FailureMix(missingDeliveryProofBps, contradictoryDeliveryBps, expiredEvidenceBps,
                duplicateEventBps, lateEventBps, outOfOrderBps, droppedEventBps, partialRefundBps,
                multiShipmentBps, paymentFailureBps, customerContactBps, refundBps, days);
    }

    private static int clamp(int bps) {
        return Math.max(0, Math.min(FULL_BPS, bps));
    }
}
