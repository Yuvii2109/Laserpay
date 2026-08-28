package com.laserpay.pdei.statebuilder.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.persistence.entity.CommunicationEntity;
import com.laserpay.pdei.persistence.repository.CommunicationRepository;
import com.laserpay.pdei.statebuilder.evidence.DerivedEvidenceService;
import com.laserpay.pdei.statebuilder.projection.ProjectionWatermark;
import com.laserpay.pdei.statebuilder.projection.ReferenceData;
import com.laserpay.pdei.statebuilder.projection.TransactionProjection;
import com.laserpay.pdei.statebuilder.support.CanonicalPayloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Projects the COMMUNICATION aggregate and derives {@code CUSTOMER_COMMUNICATION}.
 *
 * <p>Communications are usually a RECOMMENDED requirement rather than a MANDATORY one, yet they are
 * the artifact that most often flips an ambiguous case: they establish what the customer was told
 * and when. A dispute claiming "nobody ever contacted me" dies against a timestamped delivery
 * notification.
 *
 * <h2>Both directions are evidence</h2>
 *
 * {@code CommunicationCreated} (merchant to customer) and {@code CommunicationReceived} (customer to
 * merchant) both derive an artifact. The inbound direction matters as much as the outbound: a
 * customer who wrote "thanks, received it" has said something a representment can quote.
 *
 * <p>A communication with no transaction is still projected - it is real history, and a later
 * correlation may attach it - but no evidence is derived from it, because evidence in this platform
 * always belongs to a transaction.
 */
public class CommunicationEventHandler implements AggregateEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CommunicationEventHandler.class);

    /** Channels the {@code ck_communications_channel} constraint accepts. */
    private static final Set<String> CHANNELS =
            Set.of("EMAIL", "SMS", "CHAT", "PHONE", "PORTAL", "WHATSAPP");

    private final CommunicationRepository communications;
    private final TransactionProjection transactionProjection;
    private final ReferenceData referenceData;
    private final DerivedEvidenceService derivedEvidence;

    public CommunicationEventHandler(CommunicationRepository communications,
                                     TransactionProjection transactionProjection,
                                     ReferenceData referenceData,
                                     DerivedEvidenceService derivedEvidence) {
        this.communications = communications;
        this.transactionProjection = transactionProjection;
        this.referenceData = referenceData;
        this.derivedEvidence = derivedEvidence;
    }

    @Override
    public Set<EventType> handles() {
        return EnumSet.of(EventType.CommunicationCreated, EventType.CommunicationReceived);
    }

    @Override
    public void handle(CanonicalEvent event) {
        JsonNode payload = event.payload();
        String communicationId = event.aggregateId();
        String transactionId = CanonicalPayloads.text(payload, "transactionId");
        String customerId = CanonicalPayloads.text(payload, "customerId");

        referenceData.ensureMerchant(event.merchantId(), null);
        if (transactionId != null) {
            transactionProjection.ensure(event, transactionId, customerId, null);
        } else if (customerId != null) {
            referenceData.ensureCustomer(customerId, event.merchantId(), event.occurredAt());
        }

        CommunicationEntity entity = communications.findById(communicationId).orElse(null);
        if (entity == null) {
            entity = new CommunicationEntity();
            entity.setId(communicationId);
            entity.setMerchantId(event.merchantId());
            entity.setOccurredAt(event.occurredAt());
            entity.setChannel("PORTAL");
            entity.setDirection(directionOf(event));
        }

        if (!ProjectionWatermark.shouldApply(entity.getMetadata(), event)) {
            log.debug("ignoring {} {} for communication {}: older than the applied watermark",
                    event.eventType(), event.eventId(), communicationId);
            return;
        }

        entity.setTransactionId(transactionId);
        entity.setCustomerId(customerId);
        entity.setChannel(channelOf(payload));
        entity.setDirection(directionOf(event));
        entity.setSubject(CanonicalPayloads.text(payload, "subject"));
        entity.setBody(CanonicalPayloads.text(payload, "bodyPreview", "body"));
        entity.setSender(CanonicalPayloads.text(payload, "sender"));
        entity.setRecipient(CanonicalPayloads.text(payload, "recipient"));
        entity.setObjectKey(CanonicalPayloads.text(payload, "objectKey"));
        entity.setSha256(CanonicalPayloads.text(payload, "sha256"));

        java.time.Instant occurredAt = CanonicalPayloads.instant(payload, "occurredAt");
        entity.setOccurredAt(occurredAt != null ? occurredAt : event.occurredAt());

        entity.setMetadata(ProjectionWatermark.stamp(entity.getMetadata(), event));
        communications.save(entity);

        if (transactionId != null) {
            derivedEvidence.derive(event, EvidenceType.CUSTOMER_COMMUNICATION, transactionId,
                    communicationId, summaryOf(entity));
        }
    }

    /**
     * The canonical event type decides the direction, not a payload field. The two must agree, and
     * the type is the half normalization already validated.
     */
    private static String directionOf(CanonicalEvent event) {
        return event.eventType() == EventType.CommunicationReceived ? "INBOUND" : "OUTBOUND";
    }

    /**
     * Constrains the channel to the vocabulary {@code ck_communications_channel} allows. An
     * unrecognised value becomes {@code PORTAL} rather than failing the insert: the channel is
     * descriptive metadata and no case decision hangs on it.
     */
    private static String channelOf(JsonNode payload) {
        String channel = CanonicalPayloads.text(payload, "channel");
        if (channel == null) {
            return "PORTAL";
        }
        String upper = channel.toUpperCase(Locale.ROOT);
        return CHANNELS.contains(upper) ? upper : "PORTAL";
    }

    private static String summaryOf(CommunicationEntity entity) {
        String direction = "INBOUND".equals(entity.getDirection())
                ? "Customer message received"
                : "Message sent to customer";
        String subject = entity.getSubject();
        return direction + " via " + entity.getChannel()
                + (subject == null ? "" : ": " + subject);
    }
}
