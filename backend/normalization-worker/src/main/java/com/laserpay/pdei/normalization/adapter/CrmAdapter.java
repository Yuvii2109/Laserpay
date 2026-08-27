package com.laserpay.pdei.normalization.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.common.id.IdPrefix;
import com.laserpay.pdei.normalization.support.Payloads;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Helpdesk / CRM conversations with the customer.
 *
 * <p>Communications are usually a RECOMMENDED requirement rather than a MANDATORY one, but they are
 * the artifact that most often flips an ambiguous case, because they establish what the customer was
 * told and when. Two things therefore matter here and are enforced:
 *
 * <ul>
 *   <li><strong>Direction is derived, not guessed.</strong> The canonical type
 *       ({@code CommunicationCreated} = merchant to customer, {@code CommunicationReceived} =
 *       customer to merchant) and the {@code direction} field always agree, because the source event
 *       name is what decides both.</li>
 *   <li><strong>Bodies are truncated, not stored whole.</strong> Only {@code bodyPreview} travels on
 *       the event bus; the full message stays in the CRM and, when it matters as evidence, is
 *       fetched into MinIO by its {@code objectKey}. Kafka is not a document store.</li>
 * </ul>
 */
public class CrmAdapter extends AbstractSourceAdapter {

    private static final Map<String, EventType> MAPPINGS = Map.ofEntries(
            Map.entry("email.sent", EventType.CommunicationCreated),
            Map.entry("message.outbound", EventType.CommunicationCreated),
            Map.entry("ticket.reply.outbound", EventType.CommunicationCreated),
            Map.entry("ticket.agent_reply", EventType.CommunicationCreated),
            Map.entry("sms.sent", EventType.CommunicationCreated),
            Map.entry("notification.sent", EventType.CommunicationCreated),
            Map.entry("email.received", EventType.CommunicationReceived),
            Map.entry("message.inbound", EventType.CommunicationReceived),
            Map.entry("ticket.reply.inbound", EventType.CommunicationReceived),
            Map.entry("ticket.created", EventType.CommunicationReceived),
            Map.entry("chat.message.customer", EventType.CommunicationReceived));

    private static final Set<String> ALIASES = Set.of(
            "CRM", "HELPDESK", "SUPPORT", "zendesk", "freshdesk", "intercom", "gorgias");

    /** Preview length that keeps an event well under any sane Kafka message budget. */
    private static final int BODY_PREVIEW_LIMIT = 512;

    public CrmAdapter(String defaultCurrency) {
        super("CRM", ALIASES, MAPPINGS, defaultCurrency);
    }

    @Override
    public EventSource eventSource() {
        return EventSource.CRM;
    }

    @Override
    protected CanonicalEvent map(RawEventEnvelope raw, EventType eventType, Instant observedAt) {
        JsonNode wrapped = Payloads.first(raw.body(), "message", "ticket", "data.message", "data",
                "payload");
        JsonNode source = wrapped == null ? raw.body() : wrapped;

        String communicationId = prefixedFrom(source, IdPrefix.COMMUNICATION, "communicationId",
                "communication_id", "messageId", "message_id", "id", "ticket_id");
        Instant occurredAt = Payloads.instantOr(source, raw.receivedAt(), "occurredAt", "occurred_at",
                "sentAt", "sent_at", "received_at", "created_at", "timestamp");

        boolean inbound = eventType == EventType.CommunicationReceived;

        ObjectNode payload = Payloads.object();
        Payloads.putText(payload, "communicationId", communicationId);
        Payloads.putText(payload, "transactionId", prefixedFrom(source, IdPrefix.TRANSACTION,
                "transactionId", "transaction_id", "metadata.transaction_id", "custom_fields.transaction_id"));
        Payloads.putText(payload, "customerId", prefixedFrom(source, IdPrefix.CUSTOMER, "customerId",
                "customer_id", "requester_id", "contact_id"));
        Payloads.putText(payload, "channel", channel(source));
        payload.put("direction", inbound ? "INBOUND" : "OUTBOUND");
        Payloads.putText(payload, "subject", Payloads.text(source, "subject", "title", "topic"));
        Payloads.putText(payload, "bodyPreview", preview(Payloads.text(source, "bodyPreview",
                "body_preview", "body", "text", "plain_body", "description")));
        Payloads.putText(payload, "sender", Payloads.text(source, "sender", "from", "from_email",
                "author_email"));
        Payloads.putText(payload, "recipient", Payloads.text(source, "recipient", "to", "to_email"));
        Payloads.putText(payload, "objectKey", Payloads.text(source, "objectKey", "object_key",
                "attachment_key", "raw_message_url"));
        Payloads.putInstant(payload, "occurredAt", occurredAt);

        return envelope(raw, eventType, communicationId, occurredAt, observedAt, payload);
    }

    /**
     * Maps the source's channel vocabulary onto the values the {@code communications} table accepts
     * ({@code EMAIL|SMS|CHAT|PHONE|PORTAL|WHATSAPP}). An unrecognised channel becomes
     * {@code PORTAL} rather than failing the event: the channel is descriptive metadata, not a fact
     * the case decision hangs on, so degrading it is safe where guessing a reason code is not.
     */
    private String channel(JsonNode source) {
        String raw = Payloads.text(source, "channel", "via", "medium", "source_channel", "type");
        if (raw == null) {
            return "EMAIL";
        }
        String normalized = normalizeKey(raw);
        return switch (normalized) {
            case "email", "mail", "eml" -> "EMAIL";
            case "sms", "text" -> "SMS";
            case "chat", "livechat", "webchat", "messenger" -> "CHAT";
            case "phone", "voice", "call" -> "PHONE";
            case "whatsapp", "wa" -> "WHATSAPP";
            case "portal", "web", "helpcenter", "inapp" -> "PORTAL";
            default -> "PORTAL";
        };
    }

    private String preview(String body) {
        if (body == null) {
            return null;
        }
        String collapsed = body.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= BODY_PREVIEW_LIMIT
                ? collapsed
                : collapsed.substring(0, BODY_PREVIEW_LIMIT) + "...";
    }
}
