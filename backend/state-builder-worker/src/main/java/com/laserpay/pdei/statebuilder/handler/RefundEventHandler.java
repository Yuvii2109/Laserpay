package com.laserpay.pdei.statebuilder.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.persistence.entity.MoneyEmbeddable;
import com.laserpay.pdei.persistence.entity.RefundEntity;
import com.laserpay.pdei.persistence.entity.TransactionEntity;
import com.laserpay.pdei.persistence.repository.RefundRepository;
import com.laserpay.pdei.statebuilder.evidence.DerivedEvidenceService;
import com.laserpay.pdei.statebuilder.projection.ProjectionWatermark;
import com.laserpay.pdei.statebuilder.projection.ReferenceData;
import com.laserpay.pdei.statebuilder.projection.TransactionProjection;
import com.laserpay.pdei.statebuilder.support.CanonicalPayloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * Projects the REFUND aggregate and derives {@code REFUND_RECEIPT}.
 *
 * <p>A refund receipt is decisive for {@code CREDIT_NOT_PROCESSED}: the cardholder's claim is
 * precisely that the merchant did not refund, and the receipt is the merchant's answer. It is
 * derived on {@code RefundProcessed} rather than {@code RefundCreated}, because a requested refund
 * that never settled proves nothing.
 *
 * <h2>The refunded rollup</h2>
 *
 * After a refund is saved, {@link TransactionProjection#apply} recomputes
 * {@code transactions.refunded_amount_minor} from the refunds table via
 * {@code sumProcessedAmountMinor} and re-derives the transaction status
 * ({@code PARTIALLY_REFUNDED} or {@code REFUNDED}). Recomputing rather than incrementing is what
 * makes a duplicate {@code RefundProcessed} harmless - an accumulator would double the refunded
 * total and there would be no way to notice afterwards.
 *
 * <p>The event catalog flags "cumulative refunded amount exceeding {@code PaymentCaptured}" as a
 * contradiction source. That detection belongs to {@code evidence-core}'s
 * {@code ContradictionDetector}, which reads these projections; this handler's job is to make the
 * numbers true.
 */
public class RefundEventHandler implements AggregateEventHandler {

    private static final Logger log = LoggerFactory.getLogger(RefundEventHandler.class);

    private final RefundRepository refunds;
    private final TransactionProjection transactionProjection;
    private final ReferenceData referenceData;
    private final DerivedEvidenceService derivedEvidence;

    public RefundEventHandler(RefundRepository refunds,
                              TransactionProjection transactionProjection,
                              ReferenceData referenceData,
                              DerivedEvidenceService derivedEvidence) {
        this.refunds = refunds;
        this.transactionProjection = transactionProjection;
        this.referenceData = referenceData;
        this.derivedEvidence = derivedEvidence;
    }

    @Override
    public Set<EventType> handles() {
        return EnumSet.of(EventType.RefundCreated, EventType.RefundProcessed);
    }

    @Override
    public void handle(CanonicalEvent event) {
        JsonNode payload = event.payload();
        String refundId = event.aggregateId();
        String transactionId = TransactionProjection.resolveTransactionId(
                CanonicalPayloads.text(payload, "transactionId"), refundId);
        Money amount = CanonicalPayloads.money(payload, "amount");

        // refunds.transaction_id is NOT NULL and a foreign key: the transaction must exist first.
        TransactionEntity transaction = transactionProjection.ensure(event, transactionId, null, null);
        String currency = amount != null ? amount.currency() : transaction.getAmount().getCurrency();

        String paymentId = CanonicalPayloads.text(payload, "paymentId");
        if (paymentId != null) {
            referenceData.ensurePayment(paymentId, event, transactionId, currency);
        }

        RefundEntity entity = refunds.findById(refundId).orElse(null);
        if (entity == null) {
            entity = new RefundEntity();
            entity.setId(refundId);
            entity.setMerchantId(event.merchantId());
            entity.setTransactionId(transactionId);
            entity.setAmount(MoneyEmbeddable.of(amount == null ? Money.zero(currency) : amount));
            entity.setStatus("CREATED");
            entity.setRequestedAt(event.occurredAt());
        }

        if (!ProjectionWatermark.shouldApply(entity.getMetadata(), event)) {
            log.debug("ignoring {} {} for refund {}: older than the applied watermark",
                    event.eventType(), event.eventId(), refundId);
            return;
        }

        if (paymentId != null) {
            entity.setPaymentId(paymentId);
        }
        if (amount != null) {
            entity.setAmount(MoneyEmbeddable.of(amount));
        }
        applyIfPresent(payload, "reason", entity::setReason);
        applyIfPresent(payload, "settlementReference", entity::setPspReference);

        switch (event.eventType()) {
            case RefundCreated -> {
                entity.setStatus("CREATED");
                entity.setRequestedAt(firstNonNull(
                        CanonicalPayloads.instant(payload, "createdAt", "requestedAt"),
                        event.occurredAt()));
            }
            case RefundProcessed -> {
                entity.setStatus("PROCESSED");
                entity.setProcessedAt(firstNonNull(
                        CanonicalPayloads.instant(payload, "processedAt"), event.occurredAt()));
                if (entity.getRequestedAt() == null) {
                    entity.setRequestedAt(entity.getProcessedAt());
                }
            }
            default -> throw new IllegalStateException(
                    "RefundEventHandler received " + event.eventType());
        }

        entity.setMetadata(ProjectionWatermark.stamp(entity.getMetadata(), event));
        refunds.save(entity);

        // Status is left to the rollup: refundStatusFor decides PARTIALLY_REFUNDED vs REFUNDED
        // from the recomputed totals rather than from this single event.
        transactionProjection.apply(event, transactionId, null);

        if (event.eventType() == EventType.RefundProcessed) {
            derivedEvidence.derive(event, EvidenceType.REFUND_RECEIPT, transactionId, refundId,
                    "Refund " + refundId + " processed"
                            + (amount == null ? "" : " for " + amount.toDisplayString()));
        }
    }

    private static void applyIfPresent(JsonNode payload, String field,
                                       java.util.function.Consumer<String> setter) {
        String value = CanonicalPayloads.text(payload, field);
        if (value != null) {
            setter.accept(value);
        }
    }

    private static <T> T firstNonNull(T candidate, T fallback) {
        return candidate != null ? candidate : fallback;
    }
}
