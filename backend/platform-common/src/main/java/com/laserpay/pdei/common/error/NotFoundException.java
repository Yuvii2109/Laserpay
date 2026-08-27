package com.laserpay.pdei.common.error;

import java.util.Map;

/** A referenced entity does not exist. Maps to HTTP 404 at api-gateway-service. */
public final class NotFoundException extends PdeiException {

    public static final String CODE = "NOT_FOUND";

    public NotFoundException(String message) {
        super(CODE, 404, message, null, null);
    }

    public NotFoundException(String entityType, String id) {
        super(CODE, 404, entityType + " not found: " + id,
                Map.of("entityType", entityType, "entityId", String.valueOf(id)), null);
    }
}
