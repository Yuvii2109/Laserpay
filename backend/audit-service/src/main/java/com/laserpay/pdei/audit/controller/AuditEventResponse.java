package com.laserpay.pdei.audit.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.event.ActorType;
import com.laserpay.pdei.common.event.AuditEvent;

import java.time.Instant;
import java.util.List;

/**
 * Wire shape of one audit entry, as served by {@code GET /audit/v1/events} and emitted as one line
 * of {@code GET /audit/v1/export}.
 *
 * <p>Field names mirror {@code com.laserpay.pdei.common.event.AuditEvent} exactly, so a client can
 * recompute the hash from the response and verify the chain itself without trusting this service -
 * which is the whole value of publishing the hashes at all. Renaming a field here would silently
 * break that: the hash is taken over the canonical JSON of these names.
 */
public record AuditEventResponse(
        String auditId,
        String entityType,
        String entityId,
        String merchantId,
        String action,
        String actor,
        ActorType actorType,
        Instant occurredAt,
        String correlationId,
        JsonNode before,
        JsonNode after,
        String previousHash,
        String hash) {

    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(
                event.auditId(),
                event.entityType(),
                event.entityId(),
                event.merchantId(),
                event.action(),
                event.actor(),
                event.actorType(),
                event.occurredAt(),
                event.correlationId(),
                event.before(),
                event.after(),
                event.previousHash(),
                event.hash());
    }

    public static List<AuditEventResponse> from(List<AuditEvent> events) {
        return events.stream().map(AuditEventResponse::from).toList();
    }
}
