package com.laserpay.pdei.statebuilder.projection;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.id.IdPrefix;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.persistence.entity.MoneyEmbeddable;
import com.laserpay.pdei.persistence.entity.PaymentEntity;
import com.laserpay.pdei.persistence.entity.TransactionEntity;
import com.laserpay.pdei.persistence.repository.PaymentRepository;
import com.laserpay.pdei.persistence.repository.RefundRepository;
import com.laserpay.pdei.persistence.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Maintains the {@code transactions} row - the readiness unit of work and the parent every other
 * projection hangs off.
 *
 * <h2>Rollups are recomputed, never incremented</h2>
 *
 * {@code captured_amount_minor} is summed from the {@code payments} rows in state {@code CAPTURED};
 * {@code refunded_amount_minor} comes from {@code RefundRepository.sumProcessedAmountMinor}. Both
 * are derived from the rows that exist, so processing the same event twice, replaying a partition,
 * or applying events out of order all converge on the same numbers.
 *
 * <p>An accumulator ({@code captured += amount}) would be shorter and wrong: a redelivered
 * {@code PaymentCaptured} would double the captured total, and there is no way to notice
 * afterwards. Recomputation is the only arithmetic on money this platform performs, and it is
 * exact integer arithmetic on minor units.
 *
 * <h2>Status</h2>
 *
 * The transaction row is written by three unordered aggregate streams (payment, refund, dispute),
 * so its status moves through {@link TransactionStatus#promote} and never regresses.
 */
public class TransactionProjection {

    private static final Logger log = LoggerFactory.getLogger(TransactionProjection.class);

    private final TransactionRepository transactions;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final ReferenceData referenceData;
    private final String defaultCurrency;

    public TransactionProjection(TransactionRepository transactions,
                                 PaymentRepository payments,
                                 RefundRepository refunds,
                                 ReferenceData referenceData,
                                 String defaultCurrency) {
        this.transactions = transactions;
        this.payments = payments;
        this.refunds = refunds;
        this.referenceData = referenceData;
        this.defaultCurrency = defaultCurrency;
    }

    /**
     * Returns the transaction row, creating it when the event is the first thing PDEI has seen about
     * this transaction. The merchant row is guaranteed first, because {@code transactions} has a
     * foreign key to it.
     *
     * @param amount the transaction's order value when the event knows it; {@code null} leaves the
     *               row at zero until an event that does know arrives
     */
    public TransactionEntity ensure(CanonicalEvent event, String transactionId, String customerId,
                                    Money amount) {
        String currency = amount != null ? amount.currency() : defaultCurrency;
        referenceData.ensureMerchant(event.merchantId(), currency);
        if (customerId != null) {
            referenceData.ensureCustomer(customerId, event.merchantId(), event.occurredAt());
        }

        TransactionEntity entity = transactions.findById(transactionId).orElseGet(() -> {
            TransactionEntity created = new TransactionEntity();
            created.setId(transactionId);
            created.setMerchantId(event.merchantId());
            created.setAmount(MoneyEmbeddable.of(amount == null ? Money.zero(currency) : amount));
            created.setCapturedAmount(MoneyEmbeddable.zero(currency));
            created.setRefundedAmount(MoneyEmbeddable.zero(currency));
            created.setStatus(TransactionStatus.CREATED);
            created.setOccurredAt(event.occurredAt());
            created.setObservedAt(event.observedAt());
            log.debug("created transaction {} from {}", transactionId, event.eventType());
            return created;
        });

        if (customerId != null && entity.getCustomerId() == null) {
            entity.setCustomerId(customerId);
        }
        // The transaction's own occurredAt is the earliest fact known about it: a late-arriving
        // OrderCreated that predates the payment legitimately moves it earlier.
        if (event.occurredAt().isBefore(entity.getOccurredAt())) {
            entity.setOccurredAt(event.occurredAt());
        }
        if (amount != null && (entity.getAmount() == null || entity.getAmount().getAmountMinor() == 0L)) {
            entity.setAmount(MoneyEmbeddable.of(amount));
        }
        return transactions.save(entity);
    }

    /**
     * Applies an aggregate event to the transaction row: promotes the status, refreshes the money
     * rollups from the child rows, and records the watermark.
     */
    public TransactionEntity apply(CanonicalEvent event, String transactionId, String candidateStatus) {
        TransactionEntity entity = transactions.findById(transactionId).orElse(null);
        if (entity == null) {
            log.warn("no transaction {} to apply {} to; skipping rollup", transactionId,
                    event.eventType());
            return null;
        }
        entity.setStatus(TransactionStatus.promote(entity.getStatus(), candidateStatus));
        refreshRollups(entity);

        entity.setLastEventId(event.eventId());
        entity.setLastEventAt(event.occurredAt());
        entity.setObservedAt(latest(entity.getObservedAt(), event.observedAt()));
        entity.setMetadata(ProjectionWatermark.stamp(entity.getMetadata(), event));
        return transactions.save(entity);
    }

    /**
     * Recomputes {@code capturedAmount} and {@code refundedAmount} from the child rows, and derives
     * the refund status from the two.
     */
    public void refreshRollups(TransactionEntity entity) {
        String currency = currencyOf(entity);

        long capturedMinor = 0L;
        List<PaymentEntity> transactionPayments = payments.findByTransactionId(entity.getId());
        for (PaymentEntity payment : transactionPayments) {
            if ("CAPTURED".equals(payment.getStatus()) && payment.getAmount() != null) {
                capturedMinor += payment.getAmount().getAmountMinor();
            }
        }
        long refundedMinor = refunds.sumProcessedAmountMinor(entity.getId());

        entity.setCapturedAmount(MoneyEmbeddable.of(capturedMinor, currency));
        entity.setRefundedAmount(MoneyEmbeddable.of(refundedMinor, currency));

        String refundStatus = TransactionStatus.refundStatusFor(refundedMinor, capturedMinor);
        if (refundStatus != null) {
            entity.setStatus(TransactionStatus.promote(entity.getStatus(), refundStatus));
        }
    }

    /**
     * The transaction id an event belongs to.
     *
     * <p>When the payload names one, that wins. When it does not - some PSPs never echo the
     * merchant's transaction reference - a deterministic id is derived from the aggregate id, so the
     * same aggregate always maps to the same transaction on every replay. The derivation is named
     * and visible rather than a silent {@code Ids.transaction()}, which would fork a new transaction
     * on every redelivery.
     */
    public static String resolveTransactionId(String fromPayload, String aggregateId) {
        if (fromPayload != null && !fromPayload.isBlank()) {
            return fromPayload.startsWith(IdPrefix.TRANSACTION)
                    ? fromPayload
                    : IdPrefix.TRANSACTION + fromPayload;
        }
        return deriveTransactionId(aggregateId);
    }

    /** Deterministic fallback transaction id: {@code TX-} plus the aggregate's bare identifier. */
    public static String deriveTransactionId(String aggregateId) {
        if (aggregateId == null || aggregateId.isBlank()) {
            return null;
        }
        int dash = aggregateId.indexOf('-');
        String bare = dash >= 0 ? aggregateId.substring(dash + 1) : aggregateId;
        return IdPrefix.TRANSACTION + bare;
    }

    private String currencyOf(TransactionEntity entity) {
        if (entity.getAmount() != null && entity.getAmount().getCurrency() != null) {
            return entity.getAmount().getCurrency();
        }
        return defaultCurrency;
    }

    private static java.time.Instant latest(java.time.Instant current, java.time.Instant candidate) {
        if (current == null) {
            return candidate;
        }
        if (candidate == null) {
            return current;
        }
        return candidate.isAfter(current) ? candidate : current;
    }
}
