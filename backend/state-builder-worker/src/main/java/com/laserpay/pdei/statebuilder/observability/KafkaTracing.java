package com.laserpay.pdei.statebuilder.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * W3C trace-context propagation across the Kafka boundary (PLATFORM-CONTRACT section 13).
 *
 * <p>A trace that stops at a topic is not a trace. The {@code traceparent} header written by
 * ingestion-service is extracted here, made current for the duration of processing, and injected
 * back onto every record this worker produces - so one Tempo trace spans the webhook, normalization,
 * state building and readiness as a single story.
 *
 * <p>Uses only {@code opentelemetry-api}: with the OTel Java agent attached the propagator is the
 * real W3C one and spans are exported; without it, {@code GlobalOpenTelemetry} is a no-op and every
 * call here degrades to doing nothing. The worker never depends on an agent being present.
 *
 * <p>The MDC keys ({@code traceId}, {@code spanId}, {@code merchantId}, {@code correlationId}) are
 * the ones the contract requires on every log line, which is what makes a Loki query pivot straight
 * to the trace.
 */
public final class KafkaTracing {

    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String MDC_MERCHANT_ID = "merchantId";
    public static final String MDC_CORRELATION_ID = "correlationId";

    private static final TextMapGetter<Headers> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Headers carrier) {
            List<String> keys = new ArrayList<>();
            carrier.forEach(header -> keys.add(header.key()));
            return keys;
        }

        @Override
        public String get(Headers carrier, String key) {
            if (carrier == null) {
                return null;
            }
            Header header = carrier.lastHeader(key);
            return header == null || header.value() == null
                    ? null
                    : new String(header.value(), StandardCharsets.UTF_8);
        }
    };

    private static final TextMapSetter<Headers> SETTER = (carrier, key, value) -> {
        if (carrier == null || value == null) {
            return;
        }
        carrier.remove(key);
        carrier.add(key, value.getBytes(StandardCharsets.UTF_8));
    };

    private KafkaTracing() {
    }

    /** Parent context carried by an inbound record's headers, or the current context. */
    public static Context extract(Headers headers) {
        if (headers == null) {
            return Context.current();
        }
        return GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), headers, GETTER);
    }

    /**
     * Writes the current trace context onto an outbound record's headers. Called for every produced
     * record - canonical events and dead letters alike, because a dead letter is precisely the case
     * where a reader most wants the trace.
     */
    public static void inject(Headers headers) {
        if (headers == null) {
            return;
        }
        GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(Context.current(), headers, SETTER);
    }

    /**
     * Makes {@code context} current and populates the MDC for the duration of {@code work}.
     *
     * <p>Both the scope and the MDC are restored in a finally block: consumer threads are pooled and
     * reused, so leaking either would attach one event's identity to the next event's logs.
     */
    public static void inScope(Context context, String merchantId, String correlationId, Runnable work) {
        Context effective = context == null ? Context.current() : context;
        String previousMerchant = MDC.get(MDC_MERCHANT_ID);
        String previousCorrelation = MDC.get(MDC_CORRELATION_ID);
        try (Scope scope = effective.makeCurrent()) {
            applySpanToMdc();
            put(MDC_MERCHANT_ID, merchantId);
            put(MDC_CORRELATION_ID, correlationId);
            work.run();
        } finally {
            restore(MDC_MERCHANT_ID, previousMerchant);
            restore(MDC_CORRELATION_ID, previousCorrelation);
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_SPAN_ID);
        }
    }

    /** Copies the current span's ids into the MDC when a recording span exists. */
    public static void applySpanToMdc() {
        SpanContext spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            MDC.put(MDC_TRACE_ID, spanContext.getTraceId());
            MDC.put(MDC_SPAN_ID, spanContext.getSpanId());
        }
    }

    private static void put(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previous);
        }
    }
}
