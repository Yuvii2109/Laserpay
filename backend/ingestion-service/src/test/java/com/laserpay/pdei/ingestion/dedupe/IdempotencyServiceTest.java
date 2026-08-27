package com.laserpay.pdei.ingestion.dedupe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laserpay.pdei.common.error.UpstreamUnavailableException;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.ingestion.config.IngestionProperties;
import com.laserpay.pdei.persistence.repository.ProcessedEventRepository;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * The dedupe guarantee, backend by backend.
 *
 * <p>The behaviour under test is not "does Redis work" but "what does ingestion promise when it
 * does not": Redis is a cache and will be unavailable, so the fallback to the Postgres ledger, and
 * the fail-open decision when both are gone, are the parts worth pinning down.
 */
class IdempotencyServiceTest {

    private IngestionProperties properties;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ProcessedEventRepository repository;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new IngestionProperties();
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        repository = mock(ProcessedEventRepository.class);
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("Redis SETNX decides on the fast path and Postgres is never touched")
    void redisIsTheFastPath() {
        when(valueOps.setIfAbsent(eq("pdei:idem:evt-1"), anyString(), any(Duration.class)))
                .thenReturn(true, false);
        IdempotencyService service = service(redis, repository);

        assertThat(service.claim("evt-1")).isEqualTo(IdempotencyService.Decision.FIRST_SEEN);
        assertThat(service.claim("evt-1")).isEqualTo(IdempotencyService.Decision.DUPLICATE);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("the TTL on the claim is the contract's 7 days")
    void usesSevenDayTtl() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        service(redis, repository).claim("evt-ttl");

        verify(valueOps).setIfAbsent("pdei:idem:evt-ttl", "1", Duration.ofDays(7));
    }

    @Test
    @DisplayName("a Redis outage falls through to processed_events under the ingestion consumer group")
    void fallsBackToPostgresWhenRedisIsDown() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        when(repository.markProcessed("evt-2", ConsumerGroups.PDEI_INGESTION_SERVICE))
                .thenReturn(true, false);
        IdempotencyService service = service(redis, repository);

        assertThat(service.claim("evt-2")).isEqualTo(IdempotencyService.Decision.FIRST_SEEN);
        assertThat(service.claim("evt-2")).isEqualTo(IdempotencyService.Decision.DUPLICATE);
        verify(repository, org.mockito.Mockito.times(2))
                .markProcessed("evt-2", ConsumerGroups.PDEI_INGESTION_SERVICE);
    }

    @Test
    @DisplayName("with no store at all, fail-open accepts the event rather than losing the fact")
    void failsOpenWhenNothingIsAvailable() {
        IdempotencyService service = service(null, null);

        assertThat(service.claim("evt-3")).isEqualTo(IdempotencyService.Decision.FIRST_SEEN);
    }

    @Test
    @DisplayName("with fail-open off, an unavailable store is a 503, not a silent accept")
    void failsClosedWhenConfigured() {
        properties.getDedupe().setFailOpen(false);
        IdempotencyService service = service(null, null);

        assertThatThrownBy(() -> service.claim("evt-4"))
                .isInstanceOf(UpstreamUnavailableException.class)
                .hasMessageContaining("evt-4");
    }

    @Test
    @DisplayName("dedupe disabled short-circuits both stores")
    void disabledSkipsEverything() {
        properties.getDedupe().setEnabled(false);
        IdempotencyService service = service(redis, repository);

        assertThat(service.claim("evt-5")).isEqualTo(IdempotencyService.Decision.FIRST_SEEN);
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("a key longer than the ledger column is folded to its SHA-256, identically in both stores")
    void longKeysAreFoldedToADigest() {
        String longKey = "x".repeat(200);
        String expected = Hashes.sha256Hex(longKey);

        assertThat(IdempotencyService.normalize(longKey)).isEqualTo(expected);
        assertThat(expected).hasSize(IdempotencyService.MAX_KEY_LENGTH);
        assertThat(IdempotencyService.normalize("short")).isEqualTo("short");
    }

    @Test
    @DisplayName("releasing a claim clears both stores so a failed publish can be retried")
    void releaseClearsBothStores() {
        IdempotencyService service = service(redis, repository);
        service.release("evt-6");

        verify(redis).delete("pdei:idem:evt-6");
        verify(repository).deleteById(any());
    }

    private IdempotencyService service(StringRedisTemplate redisTemplate, ProcessedEventRepository repo) {
        return new IdempotencyService(provider(redisTemplate), provider(repo), properties);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }
}
