package com.laserpay.pdei.core.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Redis-backed implementation of {@link AiBudgetGate} using the key namespace of platform contract 12:
 * {@code pdei:ai:bucket} (token bucket) and {@code pdei:ai:budget:{yyyy-MM-dd}} (daily call budget).
 *
 * <p>The bucket is a genuine token bucket implemented as one atomic Lua script: tokens refill
 * continuously at {@code refillPerSecond} up to {@code capacity}, so a burst of ambiguous cases can
 * drain the accumulated allowance and then settles at the sustained rate.</p>
 *
 * <p><b>Failure mode is closed.</b> If Redis is unreachable the gate refuses, and the case falls back
 * to the deterministic path. Failing open would mean an unbounded spend on the one day the cache is
 * down; the deterministic path is always available, so refusing costs correctness nothing.</p>
 */
public class RedisAiBudgetGate implements AiBudgetGate {

    private static final Logger log = LoggerFactory.getLogger(RedisAiBudgetGate.class);
    private static final String BUCKET_KEY = "pdei:ai:bucket";
    private static final String BUDGET_KEY_PREFIX = "pdei:ai:budget:";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);

    private static final String BUCKET_SCRIPT = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local refill = tonumber(ARGV[3])
            local tokens = tonumber(redis.call('HGET', key, 'tokens'))
            local stamp = tonumber(redis.call('HGET', key, 'ts'))
            if tokens == nil or stamp == nil then
              tokens = capacity
              stamp = now
            end
            local elapsed = now - stamp
            if elapsed < 0 then elapsed = 0 end
            tokens = math.min(capacity, tokens + elapsed * refill)
            local allowed = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end
            redis.call('HSET', key, 'tokens', tokens, 'ts', now)
            redis.call('EXPIRE', key, 3600)
            return allowed
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> bucketScript;
    private final double capacity;
    private final double refillPerSecond;
    private final long dailyBudget;

    public RedisAiBudgetGate(StringRedisTemplate redis, double capacity, double refillPerSecond,
                             long dailyBudget) {
        this.redis = redis;
        this.capacity = capacity <= 0 ? 30 : capacity;
        this.refillPerSecond = refillPerSecond <= 0 ? 0.5d : refillPerSecond;
        this.dailyBudget = dailyBudget <= 0 ? 500L : dailyBudget;
        this.bucketScript = new DefaultRedisScript<>(BUCKET_SCRIPT, Long.class);
    }

    @Override
    public boolean tryConsumeToken() {
        try {
            Long allowed = redis.execute(bucketScript, List.of(BUCKET_KEY),
                    String.valueOf(Instant.now().getEpochSecond()),
                    String.valueOf(capacity),
                    String.valueOf(refillPerSecond));
            return allowed != null && allowed == 1L;
        } catch (RuntimeException e) {
            log.warn("AI token bucket unavailable, refusing admission (fail closed): {}", e.toString());
            return false;
        }
    }

    @Override
    public boolean tryConsumeDailyBudget() {
        String key = budgetKey();
        try {
            Long used = redis.opsForValue().increment(key);
            if (used == null) {
                return false;
            }
            if (used == 1L) {
                redis.expire(key, Duration.ofDays(2));
            }
            if (used > dailyBudget) {
                redis.opsForValue().decrement(key);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("AI daily budget unavailable, refusing admission (fail closed): {}", e.toString());
            return false;
        }
    }

    @Override
    public void refund() {
        try {
            redis.opsForValue().decrement(budgetKey());
        } catch (RuntimeException e) {
            log.debug("could not refund AI budget slot: {}", e.toString());
        }
    }

    @Override
    public long usedToday() {
        try {
            String value = redis.opsForValue().get(budgetKey());
            return value == null ? 0L : Long.parseLong(value);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    @Override
    public long dailyBudget() {
        return dailyBudget;
    }

    private static String budgetKey() {
        return BUDGET_KEY_PREFIX + DAY.format(Instant.now());
    }
}
