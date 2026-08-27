package com.laserpay.pdei.simulator.chaos;

import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.simulator.config.SimulatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Infrastructure-level chaos: killing a worker, restarting a consumer, slowing one down.
 *
 * <h2>Two mechanisms, one of them honest about being a fallback</h2>
 * <ol>
 *   <li><strong>Docker Engine API</strong> ({@link ChaosResult#MODE_DOCKER_API}). When
 *       {@code pdei.simulator.chaos.docker-enabled} is true, this posts to
 *       {@code /containers/{name}/kill}, {@code /restart}, {@code /pause} and {@code /unpause}
 *       over plain HTTP against {@code pdei.simulator.chaos.docker-host}. That is a real killed
 *       process: Temporal has to recover the workflow, the consumer group has to rebalance, and
 *       the demo shows durable recovery rather than describing it. It is OFF by default because
 *       exposing the Docker socket is equivalent to handing out root on the host.</li>
 *   <li><strong>Redis control directive</strong>
 *       ({@link ChaosResult#MODE_REDIS_CONTROL_DIRECTIVE}). The documented deterministic fallback:
 *       the instruction is written to {@code pdei:sim:control:{service}} with a TTL, where any
 *       PDEI worker may read and honour it. Nothing is faked - the injection record says plainly
 *       which mechanism was used, so a chaos history never claims a container was killed when it
 *       was not.</li>
 * </ol>
 *
 * <p>The fallback's limitation is real and recorded in the module's context.md: the other
 * services do not yet read that key, so a directive is currently a durable, auditable request
 * rather than an executed action. Enable Docker control for a genuine kill.
 */
@Component
public class WorkerControl {

    /** What a control directive asks a service to do. */
    public enum Directive {
        /** Stop consuming and exit; the container runtime restarts it. */
        KILL,
        /** Stop and restart the Kafka listener containers, forcing a group rebalance. */
        RESTART_CONSUMER,
        /** Add an artificial per-record delay for a bounded window. */
        SLOW
    }

    /** Outcome of one control action. */
    public record ControlOutcome(boolean applied, String mode, String detail) {
    }

    private static final Logger log = LoggerFactory.getLogger(WorkerControl.class);

    private final SimulatorProperties properties;
    private final ObjectProvider<StringRedisTemplate> redisTemplates;
    private final Clocks clock;
    private final HttpClient httpClient;

    public WorkerControl(SimulatorProperties properties,
                         ObjectProvider<StringRedisTemplate> redisTemplates,
                         Clocks clock) {
        this.properties = properties;
        this.redisTemplates = redisTemplates;
        this.clock = clock;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getChaos().getDockerTimeout())
                .build();
    }

    /** Kill the container backing {@code service}, or record the directive. */
    public ControlOutcome kill(String service) {
        return act(service, Directive.KILL, 0L, "kill", null);
    }

    /**
     * Restart the container backing {@code service}. For a Kafka consumer this is the interesting
     * one: the group rebalances, offsets are re-read, and every in-flight event is redelivered -
     * which is precisely the case idempotency exists to survive.
     */
    public ControlOutcome restart(String service) {
        return act(service, Directive.RESTART_CONSUMER, 0L, "restart", null);
    }

    /**
     * Slow a service down for {@code millis}. Under Docker this is pause + unpause, which stalls
     * the process without killing it and produces genuine consumer lag.
     */
    public ControlOutcome slow(String service, long millis) {
        return act(service, Directive.SLOW, millis, "pause", "unpause");
    }

    // -------------------------------------------------------------------------------------

    private ControlOutcome act(String service, Directive directive, long millis,
                               String dockerAction, String dockerUndoAction) {
        if (properties.getChaos().isDockerEnabled()) {
            try {
                String container = containerName(service);
                dockerPost(container, dockerAction);
                if (dockerUndoAction != null && millis > 0) {
                    sleep(millis);
                    dockerPost(container, dockerUndoAction);
                }
                return new ControlOutcome(true, ChaosResult.MODE_DOCKER_API,
                        "docker " + dockerAction + " " + container
                                + (millis > 0 ? " for " + millis + "ms" : ""));
            } catch (IOException | InterruptedException | RuntimeException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("docker control failed for {} ({}); falling back to a control directive: {}",
                        service, dockerAction, e.toString());
                // fall through to the documented fallback
            }
        }
        return writeDirective(service, directive, millis);
    }

    /**
     * Writes the directive to {@code pdei:sim:control:{service}}.
     *
     * @return an outcome that is honest about what actually happened
     */
    private ControlOutcome writeDirective(String service, Directive directive, long millis) {
        StringRedisTemplate redis = redisTemplates.getIfAvailable();
        String key = properties.getChaos().getControlKeyPrefix() + service;
        Duration ttl = properties.getChaos().getControlTtl();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("directive", directive.name());
        payload.put("service", service);
        payload.put("issuedAt", clock.now().toString());
        payload.put("expiresAt", clock.now().plus(ttl).toString());
        if (millis > 0) {
            payload.put("delayMs", millis);
        }

        if (redis == null) {
            log.warn("no Redis available; chaos directive {} for {} could not be recorded",
                    directive, service);
            return new ControlOutcome(false, ChaosResult.MODE_REDIS_CONTROL_DIRECTIVE,
                    "no Redis connection; directive not written");
        }
        try {
            redis.opsForValue().set(key, Json.write(payload), ttl);
            log.info("chaos control directive {} written to {} (ttl {})", directive, key, ttl);
            return new ControlOutcome(true, ChaosResult.MODE_REDIS_CONTROL_DIRECTIVE,
                    "directive " + directive + " written to " + key + " with TTL " + ttl);
        } catch (RuntimeException e) {
            log.warn("could not write chaos directive to {}: {}", key, e.toString());
            return new ControlOutcome(false, ChaosResult.MODE_REDIS_CONTROL_DIRECTIVE, e.toString());
        }
    }

    private void dockerPost(String container, String action) throws IOException, InterruptedException {
        URI uri = URI.create(properties.getChaos().getDockerHost()
                + "/containers/" + container + "/" + action);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.getChaos().getDockerTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        // 204 No Content is success; 304 means already in that state, which is also fine.
        if (response.statusCode() >= 400) {
            throw new IOException("docker " + action + " " + container + " returned "
                    + response.statusCode() + ": " + response.body());
        }
    }

    /** {@code readiness-worker} becomes {@code pdei-readiness-worker}. */
    private String containerName(String service) {
        String prefix = properties.getChaos().getContainerPrefix();
        String name = service == null || service.isBlank() ? "unknown" : service.strip();
        return name.startsWith(prefix) ? name : prefix + name;
    }

    private static void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
