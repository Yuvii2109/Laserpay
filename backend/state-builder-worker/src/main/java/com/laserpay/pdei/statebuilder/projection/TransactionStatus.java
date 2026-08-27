package com.laserpay.pdei.statebuilder.projection;

import java.util.List;

/**
 * The lifecycle ladder for {@code transactions.status}.
 *
 * <p>A ladder is needed here and nowhere else. Per-aggregate rows (a payment, a shipment) are
 * written only by events about that aggregate, which share a partition key and therefore arrive in
 * order - {@link ProjectionWatermark} is sufficient for them, and their status is set directly from
 * the event.
 *
 * <p>The transaction row is different: payments, refunds and disputes all write to it, and those are
 * <em>different</em> aggregates on <em>different</em> partitions. Nothing orders them. Without a
 * ladder, a {@code PaymentCreated} arriving after a {@code RefundProcessed} would drag a refunded
 * transaction back to CREATED.
 *
 * <p>Values and their order match the {@code ck_transactions_status} check constraint in
 * {@code V2__transactions.sql}. {@code FAILED} sits deliberately low: a failed attempt followed by a
 * successful retry on the same transaction should end as {@code CAPTURED}, and a {@code CREATED}
 * arriving afterwards should not undo the failure.
 */
public final class TransactionStatus {

    public static final String CREATED = "CREATED";
    public static final String FAILED = "FAILED";
    public static final String AUTHORIZED = "AUTHORIZED";
    public static final String CAPTURED = "CAPTURED";
    public static final String SETTLED = "SETTLED";
    public static final String PARTIALLY_REFUNDED = "PARTIALLY_REFUNDED";
    public static final String REFUNDED = "REFUNDED";
    public static final String CHARGEBACK = "CHARGEBACK";

    /** Lowest to highest. Index is the rank. */
    private static final List<String> LADDER = List.of(
            CREATED, FAILED, AUTHORIZED, CAPTURED, SETTLED, PARTIALLY_REFUNDED, REFUNDED, CHARGEBACK);

    private TransactionStatus() {
    }

    /**
     * The status the row should hold after observing {@code candidate}.
     *
     * <p>Monotonic: never returns a status ranked below {@code current}. An unknown value is treated
     * as unrankable and loses to any known one, so a future producer's vocabulary cannot corrupt an
     * existing row.
     */
    public static String promote(String current, String candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return LADDER.contains(candidate) ? candidate : CREATED;
        }
        int currentRank = LADDER.indexOf(current);
        int candidateRank = LADDER.indexOf(candidate);
        if (candidateRank < 0) {
            return current;
        }
        if (currentRank < 0) {
            return candidate;
        }
        return candidateRank >= currentRank ? candidate : current;
    }

    /** Rank of a status; {@code -1} when it is outside the ladder. */
    public static int rank(String status) {
        return status == null ? -1 : LADDER.indexOf(status);
    }

    /**
     * The refund status implied by how much of the captured amount has been returned.
     * Comparison is on {@code long} minor units - the only way money is ever compared here.
     */
    public static String refundStatusFor(long refundedMinor, long capturedMinor) {
        if (refundedMinor <= 0L) {
            return null;
        }
        return capturedMinor > 0L && refundedMinor >= capturedMinor ? REFUNDED : PARTIALLY_REFUNDED;
    }
}
