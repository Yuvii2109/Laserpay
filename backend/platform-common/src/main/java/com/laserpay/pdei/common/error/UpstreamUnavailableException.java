package com.laserpay.pdei.common.error;

import java.util.Map;

/**
 * A downstream dependency (ai-reasoning-service, MinIO, Postgres, Redis, Kafka, Temporal) is
 * unreachable or returned a transient failure.
 *
 * <p>The only {@link PdeiException} for which {@link PdeiException#isRetryable()} is true, so
 * Temporal activities and Kafka consumers retry it with backoff before dead-lettering.
 */
public final class UpstreamUnavailableException extends PdeiException {

    public static final String CODE = "UPSTREAM_UNAVAILABLE";

    public UpstreamUnavailableException(String upstream, String message) {
        super(CODE, 503, upstream + " unavailable: " + message, Map.of("upstream", upstream), null);
    }

    public UpstreamUnavailableException(String upstream, String message, Throwable cause) {
        super(CODE, 503, upstream + " unavailable: " + message, Map.of("upstream", upstream), cause);
    }
}
