package com.laserpay.pdei.statebuilder.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.persistence.entity.DisputeEntity;
import com.laserpay.pdei.persistence.entity.MoneyEmbeddable;
import com.laserpay.pdei.persistence.entity.TransactionEntity;
import com.laserpay.pdei.persistence.repository.DisputeRepository;
import com.laserpay.pdei.statebuilder.forward.EventForwarder;
import com.laserpay.pdei.statebuilder.projection.ProjectionWatermark;
import com.laserpay.pdei.statebuilder.projection.ReferenceData;
import com.laserpay.pdei.statebuilder.projection.TransactionProjection;
import com.laserpay.pdei.statebuilder.projection.TransactionStatus;
import com.laserpay.pdei.statebuilder.support.CanonicalPayloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Projects the DISPUTE aggregate and forwards dispute events to the orchestrator.
 *
 * <h2>The fan-out</h2>
 *
 * {@code DisputeCreated} on the canonical topic is forwarded to {@code pdei.dispute.events.v1},
 * where {@code case-orchestrator-service} picks it up and starts {@code DisputeCaseWorkflow}
 * (workflow id {@code case-{caseId}}). {@code DisputeUpdated} and {@code DisputeClosed} are
 * forwarded too: the running workflow consumes them as the {@code disputeUpdated} signal and as the
 * end of its follow-up loop (PLATFORM-CONTRACT §10).
 *
 * <p>Forwarding preserves the event exactly - same {@code eventId}, same key, same headers - so the
 * orchestrator's own idempotency sees one event however many times it is delivered.
 *
 * <h2>Order of operations</h2>
 *
 * The projection is written <em>before</em> the forward. The orchestrator's first act is to read the
 * dispute and its transaction; a forward that outran the write would produce a workflow whose
 * opening query finds nothing. Both happen in one transaction, and the forward blocks on the broker
 * acknowledgement, so a broker failure rolls the projection back rather than leaving a dispute
 * recorded that nobody works.
 *
 * <h2>No status regression</h2>
 *
 * {@code DisputeUpdated} carries the status a PSP asserts. It is applied only when the row's
 * watermark allows it, which stops a redelivered "OPEN" from pulling a dispute back out of
 * {@code REPRESENTMENT_PREPARED}.
 */
public class DisputeEventHandler implements AggregateEventHandler {

    private static final Logger log = LoggerFactory.getLogger(DisputeEventHandler.class);

    private final DisputeRepository disputes;
    private final TransactionProjection transactionProjection;
    private final ReferenceData referenceData;
    private final EventForwarder forwarder;

    public DisputeEventHandler(DisputeRepository disputes,
                               TransactionProjection transactionProjection,
                               ReferenceData referenceData,
                               EventForwarder forwarder) {
        this.disputes = disputes;
        this.transactionProjection = transactionProjection;
        this.referenceData = referenceData;
        this.forwarder = forwarder;
    }

    @Override
    public Set<EventType> handles() {
        return EnumSet.of(EventType.DisputeCreated, EventType.DisputeUpdated, EventType.DisputeClosed);
    }

    @Override
    public void handle(CanonicalEvent event) {
        JsonNode payload = event.payload();
        String disputeId = event.aggregateId();

        DisputeEntity entity = disputes.findById(disputeId).orElse(null);
        if (entity == null && event.eventType() != EventType.DisputeCreated
                && CanonicalPayloads.text(payload, "reasonCode") == null) {
            // An update or closure overtook the creation it belongs to. The row cannot be created
            // without a reason code (NOT NULL, and it selects the requirement profile), so the
            // projection waits for DisputeCreated - but the event is still forwarded, because the
            // orchestrator may have a workflow waiting on exactly this signal.
            log.warn("{} {} arrived before DisputeCreated for {}; forwarding without projecting",
                    event.eventType(), event.eventId(), disputeId);
            forwarder.forward(Topics.DISPUTE_EVENTS, event);
            return;
        }

        String transactionId = TransactionProjection.resolveTransactionId(
                CanonicalPayloads.text(payload, "transactionId"),
                entity != null ? entity.getTransactionId() : disputeId);
        Money amount = CanonicalPayloads.money(payload, "disputedAmount", "amount");

        // disputes.transaction_id is NOT NULL with a foreign key.
        TransactionEntity transaction = transactionProjection.ensure(event, transactionId, null, null);
        String currency = amount != null ? amount.currency() : transaction.getAmount().getCurrency();

        String paymentId = CanonicalPayloads.text(payload, "paymentId");
        if (paymentId != null) {
            referenceData.ensurePayment(paymentId, event, transactionId, currency);
        }

        if (entity == null) {
            entity = new DisputeEntity();
            entity.setId(disputeId);
            entity.setMerchantId(event.merchantId());
            entity.setTransactionId(transactionId);
            entity.setAmount(MoneyEmbeddable.of(amount == null ? Money.zero(currency) : amount));
            entity.setStatus(DisputeStatus.OPEN);
            entity.setReasonCode(reasonCodeOf(payload, event));
            entity.setOpenedAt(event.occurredAt());
            entity.setSource(event.source());
        }

        if (!ProjectionWatermark.shouldApply(entity.getMetadata(), event)) {
            log.debug("ignoring {} {} for dispute {}: older than the applied watermark",
                    event.eventType(), event.eventId(), disputeId);
            // Still forward: the orchestrator dedupes on eventId and a suppressed projection write
            // must not silently drop a signal the workflow may be waiting on.
            forwarder.forward(Topics.DISPUTE_EVENTS, event);
            return;
        }

        if (amount != null) {
            entity.setAmount(MoneyEmbeddable.of(amount));
        }
        applyIfPresent(payload, "pspDisputeRef", entity::setPspDisputeRef);
        applyIfPresent(payload, "network", entity::setNetwork);
        applyIfPresent(payload, "note", entity::setDescription);

        String transactionStatus = null;
        switch (event.eventType()) {
            case DisputeCreated -> {
                entity.setReasonCode(reasonCodeOf(payload, event));
                entity.setStatus(DisputeStatus.OPEN);
                entity.setOpenedAt(firstNonNull(
                        CanonicalPayloads.instant(payload, "receivedAt", "createdAt"),
                        event.occurredAt()));
                entity.setDeadlineAt(CanonicalPayloads.instant(payload, "deadlineAt"));
                entity.setSource(event.source());
                // A disputed transaction is in chargeback, and nothing walks that back.
                transactionStatus = TransactionStatus.CHARGEBACK;
            }
            case DisputeUpdated -> {
                DisputeStatus status = statusOf(CanonicalPayloads.text(payload, "status"));
                if (status != null) {
                    entity.setStatus(status);
                }
                java.time.Instant deadline = CanonicalPayloads.instant(payload, "deadlineAt");
                if (deadline != null) {
                    entity.setDeadlineAt(deadline);
                }
            }
            case DisputeClosed -> {
                String outcome = normalizedOutcome(CanonicalPayloads.text(payload, "outcome"));
                entity.setOutcome(outcome);
                entity.setStatus(closedStatusFor(outcome));
                entity.setClosedAt(firstNonNull(
                        CanonicalPayloads.instant(payload, "closedAt"), event.occurredAt()));
            }
            default -> throw new IllegalStateException(
                    "DisputeEventHandler received " + event.eventType());
        }

        entity.setLastEventId(event.eventId());
        // The card network's own reason code has no column of its own: it is kept alongside the
        // canonical DisputeReasonCode so an analyst can still see what the network actually said.
        java.util.Map<String, Object> metadata =
                ProjectionWatermark.stamp(entity.getMetadata(), event);
        String networkReasonCode = CanonicalPayloads.text(payload, "networkReasonCode");
        if (networkReasonCode != null) {
            metadata.put("networkReasonCode", networkReasonCode);
        }
        entity.setMetadata(metadata);
        disputes.save(entity);

        if (transactionStatus != null) {
            transactionProjection.apply(event, transactionId, transactionStatus);
        }

        forwarder.forward(Topics.DISPUTE_EVENTS, event);
    }

    // --- vocabulary -----------------------------------------------------------------------------

    /**
     * The reason code selects the evidence requirement profile, so an unreadable one is a hard
     * failure rather than a default. normalization-worker already refused unmapped codes; anything
     * reaching here with a bad value is a contract violation and must be dead-lettered.
     */
    private static DisputeReasonCode reasonCodeOf(JsonNode payload, CanonicalEvent event) {
        String value = CanonicalPayloads.text(payload, "reasonCode");
        if (value == null) {
            throw new ValidationException("DisputeCreated " + event.eventId()
                    + " carries no reasonCode; a dispute cannot be projected without one");
        }
        try {
            return DisputeReasonCode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("unknown DisputeReasonCode '" + value + "' on event "
                    + event.eventId(), e);
        }
    }

    /** Tolerant status read: an unknown value leaves the current status untouched. */
    private static DisputeStatus statusOf(String value) {
        if (value == null) {
            return null;
        }
        try {
            return DisputeStatus.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code ck_disputes_outcome} accepts WON, LOST, WITHDRAWN, EXPIRED. */
    private static String normalizedOutcome(String value) {
        if (value == null) {
            return "WITHDRAWN";
        }
        String upper = value.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "WON", "LOST", "WITHDRAWN", "EXPIRED" -> upper;
            default -> "WITHDRAWN";
        };
    }

    private static DisputeStatus closedStatusFor(String outcome) {
        return switch (outcome) {
            case "WON" -> DisputeStatus.WON;
            case "LOST" -> DisputeStatus.LOST;
            case "EXPIRED" -> DisputeStatus.EXPIRED;
            default -> DisputeStatus.WITHDRAWN;
        };
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
