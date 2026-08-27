package com.laserpay.pdei.orchestrator.activity;

import com.laserpay.pdei.common.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Makes the handful of activities that are <em>not</em> naturally idempotent behave as if they
 * were.
 *
 * <p>Most activities in this module are safe to repeat because they are reads plus whole-value
 * upserts. Four are not:</p>
 * <ul>
 *   <li>{@code runAdmissionControl} spends a Redis budget token and appends an admission-log row;</li>
 *   <li>{@code investigate} mints an investigation id and may spend a model call;</li>
 *   <li>{@code validateAndGate} appends to the hash-chained audit log;</li>
 *   <li>{@code prepareRepresentmentPackage} writes a NEW package version on every call.</li>
 * </ul>
 *
 * <p>Each of those receives a deterministic token from the workflow ({@code caseId:rN:label}) and
 * runs through {@link #remember}: the first attempt computes and caches the result under
 * {@code pdei:case:{caseId}:memo:{token}}; a Temporal retry of the same attempt returns the cached
 * value instead of doing the work twice.</p>
 *
 * <p><b>Documented fallback.</b> With no Redis available this class degrades to
 * {@link #recomputeAlways}: the supplier runs every time. That is the correct trade-off - a
 * duplicated package version or an extra admission-log row is recoverable, refusing to process
 * disputes because a cache is down is not - but it does mean retry idempotency for these four
 * activities is best-effort without Redis. See this module's context.md, "Known gaps".</p>
 */
@Component
public class ActivityMemo {

    private static final Logger log = LoggerFactory.getLogger(ActivityMemo.class);

    /** Long enough to cover the whole retry policy (1s..60s x 10 attempts) many times over. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final String KEY_PREFIX = "pdei:case:";
    private static final String KEY_INFIX = ":memo:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    @Autowired
    public ActivityMemo(ObjectProvider<StringRedisTemplate> redisTemplates) {
        this(redisTemplates.getIfAvailable(), DEFAULT_TTL);
    }

    public ActivityMemo(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? DEFAULT_TTL : ttl;
        if (redis == null) {
            log.warn("no Redis available: activity memoisation is disabled and retries of"
                    + " runAdmissionControl / investigate / validateAndGate /"
                    + " prepareRepresentmentPackage will recompute");
        }
    }

    /** True when results are actually cached; false when the class is in recompute-always mode. */
    public boolean isEnabled() {
        return redis != null;
    }

    /**
     * Return the memoised result for {@code token}, computing and caching it on first sight.
     *
     * @param caseId   scopes the key into the {@code pdei:case:{caseId}:*} namespace of contract 12
     * @param token    deterministic per workflow attempt, supplied by the workflow
     * @param type     result type; results are stored as PDEI-canonical JSON
     * @param supplier the actual work
     */
    public <T> T remember(String caseId, String token, Class<T> type, Supplier<T> supplier) {
        if (redis == null || caseId == null || token == null) {
            return recomputeAlways(supplier);
        }
        String key = key(caseId, token);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                log.debug("activity memo hit for {}", key);
                return Json.read(cached, type);
            }
        } catch (RuntimeException e) {
            log.warn("could not read activity memo {} ({}); recomputing", key, e.toString());
            return recomputeAlways(supplier);
        }

        T value = supplier.get();
        try {
            if (value != null) {
                redis.opsForValue().set(key, Json.write(value), ttl);
            }
        } catch (RuntimeException e) {
            // The work is already done and committed; failing to cache it only costs a repeat.
            log.warn("could not store activity memo {} ({})", key, e.toString());
        }
        return value;
    }

    /** Drop a memo, so the next call recomputes. Used by ops tooling and by tests. */
    public void forget(String caseId, String token) {
        if (redis == null || caseId == null || token == null) {
            return;
        }
        try {
            redis.delete(key(caseId, token));
        } catch (RuntimeException e) {
            log.warn("could not clear activity memo for case {} token {}: {}", caseId, token,
                    e.toString());
        }
    }

    /** The no-Redis path, named so it shows up honestly in a stack trace. */
    private static <T> T recomputeAlways(Supplier<T> supplier) {
        return supplier.get();
    }

    private static String key(String caseId, String token) {
        return KEY_PREFIX + caseId + KEY_INFIX + token;
    }
}
