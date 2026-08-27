package com.laserpay.pdei.normalization.upcast;

import com.laserpay.pdei.common.event.RawEventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

/**
 * Renames source event types that upstream systems have retired.
 *
 * <p>Vendors rename webhook events (a PSP's {@code charge.succeeded} became
 * {@code payment_intent.succeeded}; a carrier's {@code pod.uploaded} became
 * {@code shipment.delivered}). Without this step, either every adapter carries both spellings
 * forever, or replaying archived events after an adapter cleanup silently dead-letters history.
 *
 * <p>Renaming here rather than in the adapters keeps each adapter's mapping table a statement about
 * the vendor's <em>current</em> vocabulary, and keeps the historical baggage in one auditable place.
 * The rename is applied regardless of declared schema version, because vendors rename events
 * without telling anyone their schema changed.
 *
 * <p>Idempotent: once renamed the new name is not in the table, so the chain terminates.
 */
public class RetiredSourceEventTypeUpcaster implements EventUpcaster {

    private static final Logger log = LoggerFactory.getLogger(RetiredSourceEventTypeUpcaster.class);

    /** Retired source event name (lower-cased) to its current replacement. */
    private static final Map<String, String> RENAMES = Map.ofEntries(
            Map.entry("charge.succeeded", "payment_intent.succeeded"),
            Map.entry("charge.pending", "payment_intent.created"),
            Map.entry("charge.dispute.funds_withdrawn", "charge.dispute.created"),
            Map.entry("payment.settled", "payment.captured"),
            Map.entry("pod.uploaded", "shipment.delivered"),
            Map.entry("tracking.pod", "shipment.delivered"),
            Map.entry("shipment.manifested", "shipment.created"),
            Map.entry("orders/updated", "orders/fulfilled"),
            Map.entry("ticket.comment.public", "ticket.reply.outbound"));

    @Override
    public int fromVersion() {
        return SchemaVersions.UNVERSIONED;
    }

    @Override
    public boolean supports(RawEventEnvelope raw) {
        return raw != null && replacementFor(raw.sourceEventType()) != null;
    }

    @Override
    public RawEventEnvelope upcast(RawEventEnvelope raw) {
        String replacement = replacementFor(raw.sourceEventType());
        if (replacement == null) {
            return raw;
        }
        log.debug("renamed retired source event type '{}' to '{}' for rawEventId={}",
                raw.sourceEventType(), replacement, raw.rawEventId());
        // Version is preserved, not bumped: a rename says nothing about the body's schema, and a
        // legacy body still needs the money upcaster to run afterwards.
        return SchemaVersions.withSourceEventType(raw, replacement, SchemaVersions.read(raw));
    }

    private static String replacementFor(String sourceEventType) {
        if (sourceEventType == null || sourceEventType.isBlank()) {
            return null;
        }
        return RENAMES.get(sourceEventType.trim().toLowerCase(Locale.ROOT));
    }
}
