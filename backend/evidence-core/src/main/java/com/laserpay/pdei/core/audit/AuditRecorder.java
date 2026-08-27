package com.laserpay.pdei.core.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.common.id.Ids;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.spi.AuditRepositoryPort;
import com.laserpay.pdei.core.spi.EventPublisherPort;
import com.laserpay.pdei.core.util.CoreErrors;
import com.laserpay.pdei.core.util.RedisLocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Append-only, hash-chained audit log.
 *
 * <p>Each entry stores the hash of its predecessor, and its own hash covers that link, so any edit
 * to a historical row invalidates every hash after it. {@code verifyChain} recomputes the whole
 * chain and reports the first divergence.</p>
 *
 * <p>Chains are per merchant: a merchant's history can be verified without reading everyone else's
 * events, and one noisy merchant cannot serialise the whole platform. The genesis link is the
 * constant {@link #GENESIS}.</p>
 *
 * <p>Appends take a short Redis lock on {@code pdei:lock:audit:{merchantId}} so two workers writing
 * at the same instant cannot both claim the same predecessor. If Redis is down the append still
 * happens - a fork in the chain is visible to verification, silently dropping an audit entry is not
 * acceptable.</p>
 */
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    public static final String GENESIS = "GENESIS";
    private static final String SYSTEM_MERCHANT = "PLATFORM";
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private final AuditRepositoryPort repository;
    private final EventPublisherPort publisher;
    private final RedisLocks locks;
    private final Clocks clock;

    public AuditRecorder(AuditRepositoryPort repository, EventPublisherPort publisher,
                         RedisLocks locks, Clocks clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.locks = locks;
        this.clock = clock;
    }

    /** Append one entry, publish it to {@code pdei.audit.events.v1} and return it. */
    public AuditEvent record(AuditCommand command) {
        CoreErrors.requireValue(command, "command");
        CoreErrors.requireText(command.entityType(), "command.entityType");
        CoreErrors.requireText(command.action(), "command.action");

        String chainKey = chainKey(command.merchantId());
        String lockToken = locks == null ? null : locks.lock("audit:" + chainKey, LOCK_TTL, 3, Duration.ofMillis(50));
        try {
            String previousHash = repository.lastHash(chainKey).orElse(GENESIS);
            AuditEvent unhashed = new AuditEvent(
                    Ids.audit(),
                    command.entityType(),
                    command.entityId(),
                    chainKey,
                    command.action(),
                    command.actor() == null ? "SYSTEM" : command.actor(),
                    command.actorType(),
                    clock.now(),
                    command.correlationId(),
                    toJson(command.before()),
                    toJson(command.after()),
                    previousHash,
                    null);
            AuditEvent event = withHash(unhashed);
            repository.append(event);
            publisher.publishAudit(event);
            return event;
        } finally {
            if (locks != null && lockToken != null) {
                locks.unlock("audit:" + chainKey, lockToken);
            }
        }
    }

    /** Convenience for the common "system did X to entity Y" case. */
    public AuditEvent recordSystem(String entityType, String entityId, String merchantId, String action,
                                   Object before, Object after) {
        return record(AuditCommand.system(entityType, entityId, merchantId, action)
                .withBefore(before)
                .withAfter(after));
    }

    /**
     * Recompute the chain for a merchant and report the first entry that does not verify.
     *
     * <p>Two things are checked per entry: that its {@code previousHash} equals the hash of the entry
     * before it, and that its own {@code hash} still matches its content.</p>
     */
    public ChainVerification verifyChain(String merchantId) {
        String chainKey = chainKey(merchantId);
        List<AuditEvent> chain = repository.findChain(chainKey, Integer.MAX_VALUE);
        String expectedPrevious = GENESIS;
        int checked = 0;
        for (AuditEvent event : chain) {
            checked++;
            if (!Objects.equals(expectedPrevious, event.previousHash())) {
                return ChainVerification.broken(chainKey, checked, event.auditId(),
                        "previousHash " + event.previousHash() + " does not match the preceding hash "
                                + expectedPrevious, clock.now());
            }
            String recomputed = event.computeHash();
            if (!Objects.equals(recomputed, event.hash())) {
                return ChainVerification.broken(chainKey, checked, event.auditId(),
                        "stored hash " + event.hash() + " does not match recomputed hash " + recomputed,
                        clock.now());
            }
            expectedPrevious = event.hash();
        }
        return ChainVerification.intact(chainKey, checked, clock.now());
    }

    /** Verify several merchant chains at once; returns only the chains that failed. */
    public List<ChainVerification> verifyChains(List<String> merchantIds) {
        List<ChainVerification> broken = new ArrayList<>();
        for (String merchantId : merchantIds) {
            ChainVerification verification = verifyChain(merchantId);
            if (!verification.intact()) {
                broken.add(verification);
            }
        }
        return List.copyOf(broken);
    }

    public List<AuditEvent> findByEntity(String entityType, String entityId, int page, int size) {
        return repository.findByEntity(entityType, entityId, page, size);
    }

    public List<AuditEvent> find(String merchantId, String actor, Instant from, Instant to, int page, int size) {
        return repository.findByFilter(merchantId, actor, from, to, page, size);
    }

    /**
     * Fill in the {@code hash} field. {@code AuditEvent.computeHash()} hashes every field except
     * {@code hash} itself, and {@code previousHash} is one of those fields, which is what links the
     * chain together.
     */
    private static AuditEvent withHash(AuditEvent event) {
        String hash = event.computeHash();
        return new AuditEvent(event.auditId(), event.entityType(), event.entityId(), event.merchantId(),
                event.action(), event.actor(), event.actorType(), event.occurredAt(),
                event.correlationId(), event.before(), event.after(), event.previousHash(), hash);
    }

    private static String chainKey(String merchantId) {
        return merchantId == null || merchantId.isBlank() ? SYSTEM_MERCHANT : merchantId;
    }

    private static JsonNode toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Json.tree(value);
        } catch (RuntimeException e) {
            log.warn("could not serialise audit payload of type {}: {}",
                    value.getClass().getSimpleName(), e.toString());
            return null;
        }
    }
}
