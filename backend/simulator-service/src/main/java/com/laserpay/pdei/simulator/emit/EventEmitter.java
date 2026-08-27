package com.laserpay.pdei.simulator.emit;

import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.simulator.config.SimulatorProperties;
import com.laserpay.pdei.simulator.world.GeneratedWorld;
import com.laserpay.pdei.simulator.world.SimEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes a generated world onto {@code pdei.raw.events.v1} at a controlled rate, with
 * backpressure and live chaos hooks.
 *
 * <h2>Rate and backpressure are different things</h2>
 * The {@link RateLimiter} sets the <em>target</em>: how fast this run wants to go. The in-flight
 * {@link Semaphore} sets the <em>ceiling</em>: how far ahead of the broker's acknowledgements the
 * emitter is allowed to run. Without the limiter, a run finishes instantly and every consumer
 * shows minutes of lag, so the demo displays a queue draining rather than a system working.
 * Without the semaphore, a slow or unavailable broker makes the producer's accumulator swallow
 * hundreds of thousands of records until the heap gives out. Both are needed, and neither
 * substitutes for the other.
 *
 * <h2>Chaos is applied here, not in the generator</h2>
 * Duplicate, drop, delay and reorder are read from {@link EmissionControl} on every event, so an
 * injection made through {@code POST /sim/v1/chaos} affects a run already in flight. The
 * generator's own {@code FailureMix} shapes the world before emission; this shapes the delivery
 * of it.
 */
@Service
public class EventEmitter {

    /** {@code pdei_sim_events_emitted_total{topic}} - the simulator's own throughput counter. */
    private static final String METRIC_EMITTED = "pdei_sim_events_emitted_total";
    private static final String METRIC_FAILED = "pdei_sim_events_failed_total";
    private static final String METRIC_DROPPED = "pdei_sim_events_dropped_total";

    private static final Logger log = LoggerFactory.getLogger(EventEmitter.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final SimulatorProperties properties;
    private final MeterRegistry meterRegistry;

    public EventEmitter(KafkaTemplate<String, Object> kafkaTemplate,
                        SimulatorProperties properties,
                        MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Result of one emission pass.
     *
     * @param emitted   records successfully handed to the broker
     * @param failed    records whose send failed
     * @param dropped   records deliberately not sent (DROP_EVENT chaos)
     * @param stopped   whether the run ended because it was asked to stop
     */
    public record EmissionResult(long emitted, long failed, long dropped, boolean stopped) {
    }

    /** Called every {@code progressUpdateEvery} events so the caller can persist progress. */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long emitted, long failed);
    }

    /**
     * Publishes every event of {@code world}, honouring rate, backpressure and live chaos.
     *
     * @param world    the generated stream
     * @param control  live control surface; stop requests and chaos budgets are read from it
     * @param listener progress callback, invoked from the emitting thread
     */
    public EmissionResult emit(GeneratedWorld world, EmissionControl control, ProgressListener listener) {
        SimulatorProperties.Emit config = properties.getEmit();
        RateLimiter limiter = new RateLimiter(config.getEventsPerSecond());
        Semaphore inFlight = new Semaphore(Math.max(1, config.getMaxInFlight()));

        AtomicLong emitted = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        long dropped = 0;
        long attempted = 0;
        boolean stopped = false;

        SimEvent held = null; // an event deferred by OUT_OF_ORDER_EVENT chaos

        for (SimEvent event : world.events()) {
            if (control.isStopped()) {
                stopped = true;
                break;
            }
            try {
                limiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped = true;
                break;
            }

            if (control.consumeDrop()) {
                dropped++;
                meterRegistry.counter(METRIC_DROPPED).increment();
                log.debug("chaos dropped event {} ({})", event.sequence(), event.canonicalType());
                continue;
            }

            long delayMillis = control.consumeDelayMillis();
            if (delayMillis > 0 && !sleep(delayMillis)) {
                stopped = true;
                break;
            }

            if (held == null && control.consumeOutOfOrder()) {
                // Hold this one back; the next event overtakes it and this is published after.
                held = event;
                continue;
            }

            send(event, config, inFlight, emitted, failed, control);
            attempted++;
            if (control.consumeDuplicate()) {
                // Byte-identical redelivery: same rawEventId, same idempotencyKey. Every
                // consumer must collapse it (platform contract 4).
                send(event, config, inFlight, emitted, failed, control);
                attempted++;
            }
            if (held != null) {
                send(held, config, inFlight, emitted, failed, control);
                attempted++;
                held = null;
            }

            // Paced off attempts, not acknowledgements: completions land asynchronously, so
            // keying the flush off `emitted` would fire erratically or not at all.
            if (attempted % Math.max(1, config.getProgressUpdateEvery()) == 0) {
                listener.onProgress(emitted.get(), failed.get());
            }
        }

        if (held != null && !stopped) {
            send(held, config, inFlight, emitted, failed, control);
        }

        awaitDrain(inFlight, config);
        listener.onProgress(emitted.get(), failed.get());
        log.info("run {} emission finished: emitted={} failed={} dropped={} stopped={}",
                control.runId(), emitted.get(), failed.get(), dropped, stopped);
        return new EmissionResult(emitted.get(), failed.get(), dropped, stopped);
    }

    /**
     * Publishes one raw envelope directly. Used by chaos types that synthesise traffic
     * (INJECT_DISPUTE) or re-publish captured traffic (DUPLICATE_EVENT, REPLAY_EVENTS).
     *
     * @param envelope    the raw event to publish
     * @param aggregateId aggregate this fact is about; forms the second half of the mandatory
     *                    partition key. Null or blank falls back to the envelope's own key.
     * @return true when the broker accepted the record
     */
    public boolean publish(RawEventEnvelope envelope, String aggregateId) {
        try {
            kafkaTemplate.send(toRecord(envelope, partitionKey(envelope, aggregateId),
                            properties.getEmit().getTopic()))
                    .get(properties.getEmit().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            meterRegistry.counter(METRIC_EMITTED, "topic", properties.getEmit().getTopic()).increment();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            meterRegistry.counter(METRIC_FAILED).increment();
            log.warn("direct publish of {} failed: {}", envelope.rawEventId(), e.toString());
            return false;
        }
    }

    // -------------------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------------------

    private void send(SimEvent event, SimulatorProperties.Emit config, Semaphore inFlight,
                      AtomicLong emitted, AtomicLong failed, EmissionControl control) {
        try {
            // Backpressure: block here rather than letting the producer buffer without limit.
            if (!inFlight.tryAcquire(config.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                failed.incrementAndGet();
                meterRegistry.counter(METRIC_FAILED).increment();
                log.warn("run {} backpressure timeout waiting for in-flight capacity", control.runId());
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        try {
            kafkaTemplate.send(toRecord(event.envelope(), event.partitionKey(), config.getTopic()))
                    .whenComplete((result, error) -> {
                        inFlight.release();
                        if (error == null) {
                            emitted.incrementAndGet();
                            meterRegistry.counter(METRIC_EMITTED, "topic", config.getTopic()).increment();
                        } else {
                            failed.incrementAndGet();
                            meterRegistry.counter(METRIC_FAILED).increment();
                            log.warn("publish of {} failed: {}", event.envelope().rawEventId(),
                                    error.toString());
                        }
                    });
            control.remember(event);
        } catch (RuntimeException e) {
            inFlight.release();
            failed.incrementAndGet();
            meterRegistry.counter(METRIC_FAILED).increment();
            log.warn("publish of sequence {} threw: {}", event.sequence(), e.toString());
        }
    }

    /**
     * Builds the Kafka record with a caller-supplied key.
     *
     * <p>The key is {@code merchantId + ":" + aggregateId} (PLATFORM-CONTRACT section 4), the same
     * scheme ingestion-service uses on this topic. Every event about one aggregate therefore lands
     * on one partition, so normalization-worker cannot process two of them concurrently and emit
     * them to {@code pdei.canonical.events.v1} out of order. It also keeps a duplicate on the same
     * partition as its original, so the same consumer instance sees both and can collapse them.
     */
    private ProducerRecord<String, Object> toRecord(RawEventEnvelope envelope, String key, String topic) {
        ProducerRecord<String, Object> record =
                new ProducerRecord<>(topic, key, envelope);
        for (Map.Entry<String, String> header : envelope.headers().entrySet()) {
            record.headers().add(header.getKey(), EventHeaders.encode(header.getValue()));
        }
        record.headers().add(EventHeaders.EVENT_ID,
                envelope.rawEventId().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    /**
     * {@code merchantId + ":" + aggregateId}, with the envelope's own key as the last resort. The
     * fallback is still merchant-scoped and still stable across redeliveries of the same fact, so
     * per-fact ordering holds even when the aggregate could not be identified.
     */
    private static String partitionKey(RawEventEnvelope envelope, String aggregateId) {
        if (aggregateId == null || aggregateId.isBlank()) {
            return envelope.partitionKey();
        }
        return envelope.merchantId() + ":" + aggregateId;
    }

    /** Waits for outstanding sends to complete so the run's final counts are accurate. */
    private void awaitDrain(Semaphore inFlight, SimulatorProperties.Emit config) {
        int permits = Math.max(1, config.getMaxInFlight());
        try {
            if (!inFlight.tryAcquire(permits, config.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("emission drain timed out; some sends may still be in flight");
                return;
            }
            inFlight.release(permits);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** @return false when the sleep was interrupted, meaning the run should stop */
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
