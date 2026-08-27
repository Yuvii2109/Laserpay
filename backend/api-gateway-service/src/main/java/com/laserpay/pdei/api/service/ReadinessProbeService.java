package com.laserpay.pdei.api.service;

import com.laserpay.pdei.api.dto.HealthResponse;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.storage.Buckets;
import com.laserpay.pdei.core.storage.ObjectStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Service;

/**
 * Backs {@code GET /api/v1/health/ready}.
 *
 * <p>Deliberately not the same thing as {@code /actuator/health}. Actuator answers an orchestrator
 * asking "should I route traffic here"; this answers the frontend asking "which panels can I
 * render". The distinction matters because the two have different opinions about what is fatal: the
 * gateway with Redis down is still perfectly able to serve every read route, so it is DEGRADED here
 * and healthy to Kubernetes.</p>
 *
 * <p>Only Postgres is required. Everything else degrades:</p>
 * <ul>
 *   <li><strong>Redis down</strong>: rate limiting fails open and stream dedupe falls back to the
 *       in-memory set. Every route still works.</li>
 *   <li><strong>Kafka down</strong>: the control tower stops receiving live frames and the UI polls
 *       instead. Every route still works.</li>
 *   <li><strong>MinIO down</strong>: downloads and uploads fail, the rest of the API does not.</li>
 * </ul>
 *
 * <p>Each probe is wrapped so that a probe failing can never fail the health endpoint itself. A
 * readiness check that throws is worse than useless: it turns a partial outage into a total one at
 * the exact moment somebody is trying to find out what is broken.</p>
 */
@Service
public class ReadinessProbeService {

    private static final Logger log = LoggerFactory.getLogger(ReadinessProbeService.class);

    private static final String SERVICE = "api-gateway-service";

    /** Probe key used against MinIO; it is never expected to exist. */
    private static final String PROBE_KEY = ".pdei-readiness-probe";

    private static final int DB_VALIDATION_TIMEOUT_SECONDS = 2;

    private final ObjectProvider<DataSource> dataSources;
    private final ObjectProvider<StringRedisTemplate> redisTemplates;
    private final ObjectProvider<ObjectStore> objectStores;
    private final ObjectProvider<KafkaListenerEndpointRegistry> listenerRegistries;
    private final Clocks clock;

    public ReadinessProbeService(ObjectProvider<DataSource> dataSources,
                                 ObjectProvider<StringRedisTemplate> redisTemplates,
                                 ObjectProvider<ObjectStore> objectStores,
                                 ObjectProvider<KafkaListenerEndpointRegistry> listenerRegistries,
                                 Clocks clock) {
        this.dataSources = dataSources;
        this.redisTemplates = redisTemplates;
        this.objectStores = objectStores;
        this.listenerRegistries = listenerRegistries;
        this.clock = clock;
    }

    public HealthResponse probe() {
        Map<String, String> dependencies = new LinkedHashMap<>();
        dependencies.put("postgres", probePostgres());
        dependencies.put("redis", probeRedis());
        dependencies.put("kafka", probeKafka());
        dependencies.put("objectStore", probeObjectStore());

        boolean postgresUp = HealthResponse.UP.equals(dependencies.get("postgres"));
        List<String> degraded = new ArrayList<>();
        dependencies.forEach((name, status) -> {
            if (!"postgres".equals(name) && HealthResponse.DOWN.equals(status)) {
                degraded.add(name);
            }
        });

        String status;
        if (!postgresUp) {
            status = HealthResponse.NOT_READY;
        } else if (degraded.isEmpty()) {
            status = HealthResponse.READY;
        } else {
            status = HealthResponse.DEGRADED;
        }
        return new HealthResponse(status, SERVICE, dependencies, degraded, clock.now());
    }

    private String probePostgres() {
        DataSource dataSource = dataSources.getIfAvailable();
        if (dataSource == null) {
            return HealthResponse.UNKNOWN;
        }
        try (var connection = dataSource.getConnection()) {
            return connection.isValid(DB_VALIDATION_TIMEOUT_SECONDS)
                    ? HealthResponse.UP : HealthResponse.DOWN;
        } catch (Exception e) {
            log.debug("Postgres readiness probe failed: {}", e.toString());
            return HealthResponse.DOWN;
        }
    }

    private String probeRedis() {
        StringRedisTemplate redis = redisTemplates.getIfAvailable();
        if (redis == null) {
            return HealthResponse.UNKNOWN;
        }
        try {
            String pong = redis.execute(connection -> connection.ping(), true);
            return pong == null ? HealthResponse.DOWN : HealthResponse.UP;
        } catch (Exception e) {
            log.debug("Redis readiness probe failed: {}", e.toString());
            return HealthResponse.DOWN;
        }
    }

    /**
     * Kafka is judged by whether the control-tower listener containers are actually running, which is
     * a truer signal than a broker ping: a container that is not running means no frames reach the
     * WebSocket, whatever the broker thinks.
     */
    private String probeKafka() {
        KafkaListenerEndpointRegistry registry = listenerRegistries.getIfAvailable();
        if (registry == null) {
            return HealthResponse.UNKNOWN;
        }
        try {
            var containers = registry.getListenerContainers();
            if (containers.isEmpty()) {
                return HealthResponse.UNKNOWN;
            }
            boolean anyRunning = containers.stream().anyMatch(MessageListenerContainer::isRunning);
            return anyRunning ? HealthResponse.UP : HealthResponse.DOWN;
        } catch (Exception e) {
            log.debug("Kafka readiness probe failed: {}", e.toString());
            return HealthResponse.DOWN;
        }
    }

    /**
     * A stat for a key that does not exist. A reachable MinIO answers "no such object" quickly; an
     * unreachable one throws. Either way nothing is written.
     */
    private String probeObjectStore() {
        ObjectStore store = objectStores.getIfAvailable();
        if (store == null) {
            return HealthResponse.UNKNOWN;
        }
        try {
            store.exists(Buckets.EVIDENCE, PROBE_KEY);
            return HealthResponse.UP;
        } catch (Exception e) {
            log.debug("Object store readiness probe failed: {}", e.toString());
            return HealthResponse.DOWN;
        }
    }

    /** The buckets this service expects to exist, exposed for diagnostics. */
    public Set<String> expectedBuckets() {
        return Set.of(Buckets.EVIDENCE, Buckets.PACKAGES);
    }
}
