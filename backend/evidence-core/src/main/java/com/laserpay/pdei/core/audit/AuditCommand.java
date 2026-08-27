package com.laserpay.pdei.core.audit;

import com.laserpay.pdei.common.event.ActorType;

/**
 * Intent to append one audit entry. The recorder turns this into a hash-chained
 * {@code com.laserpay.pdei.common.event.AuditEvent}.
 *
 * <p>{@code before} / {@code after} are arbitrary objects; they are serialised to canonical JSON so
 * the chain hash is stable regardless of field order.</p>
 */
public record AuditCommand(
        String entityType,
        String entityId,
        String merchantId,
        String action,
        String actor,
        ActorType actorType,
        String correlationId,
        Object before,
        Object after) {

    public static AuditCommand of(String entityType, String entityId, String merchantId,
                                  String action, String actor, ActorType actorType) {
        return new AuditCommand(entityType, entityId, merchantId, action,
                actor == null ? "SYSTEM" : actor,
                actorType == null ? ActorType.SYSTEM : actorType,
                null, null, null);
    }

    /** Action performed by the platform itself, with no human or model involved. */
    public static AuditCommand system(String entityType, String entityId, String merchantId, String action) {
        return of(entityType, entityId, merchantId, action, "SYSTEM", ActorType.SYSTEM);
    }

    public AuditCommand withBefore(Object value) {
        return new AuditCommand(entityType, entityId, merchantId, action, actor, actorType,
                correlationId, value, after);
    }

    public AuditCommand withAfter(Object value) {
        return new AuditCommand(entityType, entityId, merchantId, action, actor, actorType,
                correlationId, before, value);
    }

    public AuditCommand withCorrelationId(String value) {
        return new AuditCommand(entityType, entityId, merchantId, action, actor, actorType,
                value, before, after);
    }
}
