package com.laserpay.pdei.audit.chain;

import com.laserpay.pdei.audit.config.AuditProperties;
import com.laserpay.pdei.audit.metrics.AuditMetrics;
import com.laserpay.pdei.audit.repository.AuditEventStore;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.event.ActorType;
import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.id.IdPrefix;
import com.laserpay.pdei.common.id.Ids;
import com.laserpay.pdei.core.util.RedisLocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The single writer of {@code pdei.audit_events}.
 *
 * <p>Every other service <em>reports</em> what it did by publishing an {@code AuditEvent}; this
 * class is what turns those reports into chain links. Concentrating the write here is the point: two
 * writers appending to one per-merchant chain would fork it, and a forked chain fails verification
 * forever afterwards even though nothing malicious happened.
 *
 * <h2>Appending one entry</h2>
 * <ol>
 *   <li><strong>Validate.</strong> Reject anything the {@code V8} check constraints would reject -
 *       a bad {@code entity_type} or a malformed {@code audit_id}. Rejection dead-letters the
 *       record, which preserves it for replay and makes the producer's bug visible, rather than
 *       silently rewriting the content of an audit entry to make it fit.</li>
 *   <li><strong>Dedupe.</strong> An {@code auditId} already stored is a redelivery. Return the
 *       stored entry; do not append a second link for the same fact.</li>
 *   <li><strong>Lock.</strong> Take {@code pdei:lock:audit:{merchantId}} so concurrent appends to
 *       one chain are rare. The lock is an optimisation, not the correctness mechanism.</li>
 *   <li><strong>Seal.</strong> Read the chain head. If the producer already sealed the entry
 *       against exactly that head and its hash verifies, store it verbatim - the producer's hash is
 *       preserved and the wire record and the stored record are byte-identical. Otherwise re-seal:
 *       set {@code previousHash} to our head and recompute {@code hash}.</li>
 *   <li><strong>Retry on conflict.</strong> {@code ux_audit_events_link} is unique on
 *       {@code (merchant_id, previous_hash)}, so if another writer claimed the same predecessor the
 *       insert fails. That is the real serialisation point: re-read the head and seal again.</li>
 * </ol>
 *
 * <p>Re-sealing changes the hash a producer published. That is correct and intended: the stored
 * chain is authoritative, and a producer's hash is a self-integrity seal on its own report, not a
 * claim about this chain's shape. The {@code auditId}, and therefore the identity of the fact, never
 * changes.
 */
public class AuditChainAppender {

    private static final Logger log = LoggerFactory.getLogger(AuditChainAppender.class);

    /** {@link RedisLocks} prefixes {@code pdei:lock:}; this is the second half of the key. */
    private static final String LOCK_RESOURCE_PREFIX = "audit:";

    /** {@code ck_audit_events_entity_type} accepts exactly the {@code AggregateType} names. */
    private static final Set<String> VALID_ENTITY_TYPES = EnumSet.allOf(AggregateType.class).stream()
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    private final AuditEventStore store;
    private final RedisLocks locks;
    private final AuditProperties properties;
    private final AuditMetrics metrics;

    public AuditChainAppender(AuditEventStore store, RedisLocks locks, AuditProperties properties,
                              AuditMetrics metrics) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.locks = locks;
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.metrics = metrics;
    }

    /** Full Redis key serialising one merchant's chain: {@code pdei:lock:audit:{merchantId}}. */
    public static String lockKey(String merchantId) {
        return "pdei:lock:" + LOCK_RESOURCE_PREFIX + ChainVerifier.chainKey(merchantId);
    }

    /**
     * Append one entry to its merchant chain.
     *
     * @return the entry as stored - which for a re-sealed record is not the entry that was passed in
     * @throws ValidationException when the record cannot legally be stored; the caller should
     *                             dead-letter it rather than retry
     */
    public AuditEvent append(AuditEvent incoming) {
        validate(incoming);

        String chainKey = ChainVerifier.chainKey(incoming.merchantId());
        if (store.exists(incoming.auditId())) {
            if (metrics != null) {
                metrics.duplicateSuppressed();
            }
            log.debug("audit entry {} already stored; skipping", incoming.auditId());
            return incoming;
        }

        String resource = LOCK_RESOURCE_PREFIX + chainKey;
        String token = locks == null ? null : locks.lock(resource, lockTtl(),
                Math.max(1, properties.getChainLockAttempts()), lockBackoff());
        try {
            return appendWithRetries(chainKey, incoming);
        } finally {
            if (locks != null && token != null) {
                locks.unlock(resource, token);
            }
        }
    }

    /**
     * Seal and insert, retrying while the database says another writer took our predecessor.
     *
     * <p>The retry is not a workaround for a race the lock failed to prevent; it is how correctness
     * is actually achieved. The lock reduces collisions, the unique index eliminates them.
     */
    private AuditEvent appendWithRetries(String chainKey, AuditEvent incoming) {
        int attempts = Math.max(1, properties.getAppendMaxAttempts());
        DataIntegrityViolationException lastFailure = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            String head = store.lastHash(chainKey).orElse(Hashes.GENESIS_HASH);
            AuditEvent sealed = seal(chainKey, incoming, head);
            try {
                store.append(sealed);
                if (metrics != null) {
                    metrics.appended(sealed.entityType(), sealed == incoming);
                }
                return sealed;
            } catch (DataIntegrityViolationException e) {
                // Someone claimed this predecessor between our read and our insert.
                lastFailure = e;
                if (metrics != null) {
                    metrics.chainConflict();
                }
                log.debug("audit chain conflict for merchant={} on attempt {}/{}: {}",
                        chainKey, attempt, attempts, e.getMostSpecificCause().getMessage());
            }
        }
        throw new IllegalStateException(
                "could not append audit entry " + incoming.auditId() + " to chain " + chainKey
                        + " after " + attempts + " attempts", lastFailure);
    }

    /**
     * Produce the record to store.
     *
     * <p>Returns {@code incoming} unchanged - by identity, which the caller uses to report whether
     * the producer's seal survived - when the producer already chained correctly against our current
     * head and its own hash verifies. Otherwise a re-sealed copy.
     */
    private AuditEvent seal(String chainKey, AuditEvent incoming, String head) {
        boolean linksToHead = Objects.equals(incoming.previousHash(), head);
        if (properties.isAcceptPresealedLinks() && linksToHead && incoming.verifyHash()
                && chainKey.equals(incoming.merchantId())) {
            return incoming;
        }
        return new AuditEvent(
                incoming.auditId(),
                incoming.entityType(),
                incoming.entityId(),
                chainKey,
                incoming.action(),
                incoming.actor(),
                incoming.actorType() == null ? ActorType.SYSTEM : incoming.actorType(),
                incoming.occurredAt(),
                incoming.correlationId(),
                incoming.before(),
                incoming.after(),
                head,
                null).withHash();
    }

    /**
     * Reject what the database would reject, with a message that names the producer's mistake.
     *
     * <p>Deliberately strict. The alternative - coercing an out-of-vocabulary {@code entityType} into
     * something that fits - would mean this service quietly altering the content of audit records,
     * which is the exact thing it exists to make impossible.
     */
    private static void validate(AuditEvent event) {
        if (event == null) {
            throw new ValidationException("audit event must not be null");
        }
        if (!Ids.hasPrefix(event.auditId(), IdPrefix.AUDIT)) {
            throw new ValidationException("auditId must start with " + IdPrefix.AUDIT
                    + " (ck_audit_events_id_prefix), got: " + event.auditId());
        }
        if (!VALID_ENTITY_TYPES.contains(event.entityType())) {
            throw new ValidationException("entityType must be an AggregateType name"
                    + " (ck_audit_events_entity_type), got: " + event.entityType());
        }
        if (event.entityId() == null || event.entityId().isBlank()) {
            throw new ValidationException("entityId is required on an audit entry");
        }
    }

    private Duration lockTtl() {
        Duration ttl = properties.getChainLockTtl();
        return ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofSeconds(30) : ttl;
    }

    private Duration lockBackoff() {
        Duration backoff = properties.getChainLockBackoff();
        return backoff == null || backoff.isNegative() ? Duration.ofMillis(50) : backoff;
    }
}
