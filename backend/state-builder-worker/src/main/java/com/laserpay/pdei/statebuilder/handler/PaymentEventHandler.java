package com.laserpay.pdei.statebuilder.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.persistence.entity.MoneyEmbeddable;
import com.laserpay.pdei.persistence.entity.PaymentEntity;
import com.laserpay.pdei.persistence.entity.TransactionEntity;
import com.laserpay.pdei.persistence.repository.PaymentRepository;
import com.laserpay.pdei.statebuilder.evidence.DerivedEvidenceService;
import com.laserpay.pdei.statebuilder.projection.ProjectionWatermark;
import com.laserpay.pdei.statebuilder.projection.TransactionProjection;
import com.laserpay.pdei.statebuilder.projection.TransactionStatus;
import com.laserpay.pdei.statebuilder.support.CanonicalPayloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * Projects the PAYMENT aggregate and derives the artifacts a payment produces.
 *
 * <p>Derived evidence (docs/event-catalog.md §1):
 * <ul>
 *   <li>{@code PaymentCaptured} -> {@code PAYMENT_PROOF} - usually the first evidence a transaction
 *       gets, and the artifact that answers "was this actually charged, and for how much".</li>
 *   <li>{@code PaymentAuthorized} -> {@code AVS_CVV_RESULT} and {@code DEVICE_FINGERPRINT}, but only
 *       when the payload actually carries them. Deriving an empty AVS artifact would create evidence
 *       that satisfies a requirement while proving nothing, which is worse than a visible gap.</li>
 * </ul>
 */
public class PaymentEventHandler implements AggregateEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventHandler.class);

    private final PaymentRepository payments;
    private final TransactionProjection transactionProjection;
    private final DerivedEvidenceService derivedEvidence;

    public PaymentEventHandler(PaymentRepository payments,
                               TransactionProjection transactionProjection,
                               DerivedEvidenceService derivedEvidence) {
        this.payments = payments;
        this.transactionProjection = transactionProjection;
        this.derivedEvidence = derivedEvidence;
    }

    @Override
    public Set<EventType> handles() {
        return EnumSet.of(EventType.PaymentCreated, EventType.PaymentAuthorized,
                EventType.PaymentCaptured, EventType.PaymentFailed);
    }

    @Override
    public void handle(CanonicalEvent event) {
        JsonNode payload = event.payload();
        String paymentId = event.aggregateId();
        String transactionId = TransactionProjection.resolveTransactionId(
                CanonicalPayloads.text(payload, "transactionId"), paymentId);
        String customerId = CanonicalPayloads.text(payload, "customerId");
        Money amount = amountOf(event, payload);

        TransactionEntity transaction =
                transactionProjection.ensure(event, transactionId, customerId, amount);
        // A payment with no stated amount still needs a currency for its NOT NULL column: take the
        // transaction's, which is the only currency this payment could plausibly be in.
        String currency = amount != null ? amount.currency() : transaction.getAmount().getCurrency();

        PaymentEntity entity = payments.findById(paymentId).orElse(null);
        if (entity == null) {
            entity = new PaymentEntity();
            entity.setId(paymentId);
            entity.setMerchantId(event.merchantId());
            entity.setTransactionId(transactionId);
            entity.setOccurredAt(event.occurredAt());
            entity.setStatus("CREATED");
            entity.setAmount(MoneyEmbeddable.of(amount == null ? Money.zero(currency) : amount));
        }

        if (!ProjectionWatermark.shouldApply(entity.getMetadata(), event)) {
            log.debug("ignoring {} {} for payment {}: older than the applied watermark",
                    event.eventType(), event.eventId(), paymentId);
            return;
        }

        applyCommonFields(entity, payload, amount);

        String transactionStatus = switch (event.eventType()) {
            case PaymentCreated -> {
                entity.setStatus("CREATED");
                entity.setOccurredAt(firstNonNull(
                        CanonicalPayloads.instant(payload, "createdAt"), event.occurredAt()));
                yield TransactionStatus.CREATED;
            }
            case PaymentAuthorized -> {
                entity.setStatus("AUTHORIZED");
                entity.setAuthorizedAt(firstNonNull(
                        CanonicalPayloads.instant(payload, "authorizedAt"), event.occurredAt()));
                entity.setAvsResult(CanonicalPayloads.text(payload, "avsResult"));
                entity.setCvvResult(CanonicalPayloads.text(payload, "cvvResult"));
                entity.setThreeDsResult(CanonicalPayloads.text(payload, "threeDsResult"));
                entity.setDeviceFingerprint(CanonicalPayloads.text(payload, "deviceFingerprint"));
                entity.setIpAddress(CanonicalPayloads.text(payload, "ipAddress"));
                yield TransactionStatus.AUTHORIZED;
            }
            case PaymentCaptured -> {
                entity.setStatus("CAPTURED");
                entity.setCapturedAt(firstNonNull(
                        CanonicalPayloads.instant(payload, "capturedAt"), event.occurredAt()));
                yield TransactionStatus.CAPTURED;
            }
            case PaymentFailed -> {
                entity.setStatus("FAILED");
                entity.setFailedAt(firstNonNull(
                        CanonicalPayloads.instant(payload, "failedAt"), event.occurredAt()));
                entity.setFailureCode(CanonicalPayloads.text(payload, "failureCode"));
                entity.setFailureMessage(CanonicalPayloads.text(payload, "failureReason",
                        "failureMessage"));
                yield TransactionStatus.FAILED;
            }
            default -> throw new IllegalStateException(
                    "PaymentEventHandler received " + event.eventType());
        };

        entity.setMetadata(ProjectionWatermark.stamp(entity.getMetadata(), event));
        payments.save(entity);

        // Rollups are refreshed after the payment row is saved, so the recomputation sees it.
        transactionProjection.apply(event, transactionId, transactionStatus);

        deriveEvidence(event, payload, transactionId, paymentId, amount);
    }

    // --- field mapping --------------------------------------------------------------------------

    private void applyCommonFields(PaymentEntity entity, JsonNode payload, Money amount) {
        if (amount != null) {
            entity.setAmount(MoneyEmbeddable.of(amount));
        }
        setIfPresent(payload, "psp", entity::setPsp);
        setIfPresent(payload, "pspReference", entity::setPspReference);
        setIfPresent(payload, "method", entity::setMethod);
        setIfPresent(payload, "cardNetwork", entity::setCardBrand);
        setIfPresent(payload, "cardLast4", entity::setCardLast4);
        setIfPresent(payload, "cardBin", entity::setCardBin);
    }

    /** The monetary field this event type carries; all of them are minor units plus a currency. */
    private Money amountOf(CanonicalEvent event, JsonNode payload) {
        return switch (event.eventType()) {
            case PaymentAuthorized -> CanonicalPayloads.money(payload, "authorizedAmount", "amount");
            case PaymentCaptured -> CanonicalPayloads.money(payload, "capturedAmount", "amount");
            default -> CanonicalPayloads.money(payload, "amount");
        };
    }

    // --- evidence -------------------------------------------------------------------------------

    private void deriveEvidence(CanonicalEvent event, JsonNode payload, String transactionId,
                                String paymentId, Money amount) {
        switch (event.eventType()) {
            case PaymentCaptured -> derivedEvidence.derive(event, EvidenceType.PAYMENT_PROOF,
                    transactionId, paymentId,
                    "Payment " + paymentId + " captured"
                            + (amount == null ? "" : " for " + amount.toDisplayString()));
            case PaymentAuthorized -> {
                // Only derive what the payload actually proves.
                if (CanonicalPayloads.text(payload, "avsResult") != null
                        || CanonicalPayloads.text(payload, "cvvResult") != null) {
                    derivedEvidence.derive(event, EvidenceType.AVS_CVV_RESULT, transactionId,
                            paymentId, "AVS/CVV verification result for payment " + paymentId);
                }
                if (CanonicalPayloads.text(payload, "deviceFingerprint") != null) {
                    derivedEvidence.derive(event, EvidenceType.DEVICE_FINGERPRINT, transactionId,
                            paymentId, "Device fingerprint captured at authorization for " + paymentId);
                }
            }
            default -> {
                // PaymentCreated and PaymentFailed assert nothing a representment can rely on.
            }
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    private static void setIfPresent(JsonNode payload, String field,
                                     java.util.function.Consumer<String> setter) {
        String value = CanonicalPayloads.text(payload, field);
        if (value != null) {
            setter.accept(value);
        }
    }

    private static Instant firstNonNull(Instant candidate, Instant fallback) {
        return candidate != null ? candidate : fallback;
    }
}
