package com.laserpay.pdei.audit.chain;

import com.laserpay.pdei.audit.config.AuditProperties;
import com.laserpay.pdei.audit.repository.AuditEventStore;
import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.time.Clocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Recomputes a merchant's audit chain from scratch and reports the first divergence.
 *
 * <p>This is the only thing that makes the audit log <em>tamper-evident</em> rather than merely
 * append-only. Two independent checks run on every entry, in this order:
 *
 * <ol>
 *   <li><strong>Link.</strong> Does {@code previousHash} equal the hash of the entry before it?
 *       A deleted row, an inserted row or a forked chain fails here.</li>
 *   <li><strong>Content.</strong> Does recomputing {@code sha256(canonicalJson(entry minus hash))}
 *       reproduce the stored {@code hash}? An edited field fails here.</li>
 * </ol>
 *
 * <p>Both are needed. Content alone would miss a deletion (every surviving row still hashes
 * correctly); link alone would miss an edit made by someone who also recomputed the chain forward
 * from it - except that they could not, because they would have to rewrite every subsequent hash,
 * and the entries are also published to {@code pdei.audit.events.v1} where a replica exists.
 *
 * <p>Verification walks {@code sequence_no}, the database-assigned order, in pages. The chain of a
 * busy merchant does not fit in memory, and the whole point of a verifier is that it works on the
 * chain that actually exists rather than on a convenient prefix of it.
 *
 * <p>The walk stops at the first failure. After one broken link every subsequent hash mismatches,
 * so continuing would produce thousands of lines describing a single event.
 */
public class ChainVerifier {

    private static final Logger log = LoggerFactory.getLogger(ChainVerifier.class);

    private static final int PAGE_SIZE = 1000;

    private final AuditEventStore store;
    private final AuditProperties properties;
    private final Clocks clock;

    public ChainVerifier(AuditEventStore store, AuditProperties properties, Clocks clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Verify one merchant chain, up to the configured maximum number of entries. */
    public ChainVerificationReport verify(String merchantId) {
        return verify(merchantId, properties.getApi().getMaxVerifyEvents());
    }

    /**
     * Verify one merchant chain.
     *
     * @param maxEvents upper bound on entries walked; a report that hits it is flagged
     *                  {@link ChainVerificationReport#truncated()} so an intact result is not
     *                  mistaken for a proof about the whole chain
     */
    public ChainVerificationReport verify(String merchantId, int maxEvents) {
        String chainKey = chainKey(merchantId);
        Instant startedAt = clock.now();
        long chainLength = store.countChain(chainKey);

        String expectedPrevious = Hashes.GENESIS_HASH;
        long cursor = 0L;
        int index = 0;
        int limit = Math.max(1, maxEvents);

        while (index < limit) {
            List<AuditEvent> page = store.findChainPage(chainKey, cursor,
                    Math.min(PAGE_SIZE, limit - index));
            if (page.isEmpty()) {
                break;
            }
            for (AuditEvent event : page) {
                ChainDivergence divergence = check(chainKey, event, index, expectedPrevious);
                if (divergence != null) {
                    log.warn("audit chain divergence for merchant={} at index={} auditId={}: {}",
                            chainKey, index, event.auditId(), divergence.detail());
                    return ChainVerificationReport.broken(chainKey, index + 1L, chainLength,
                            divergence, clock.now());
                }
                expectedPrevious = event.hash();
                index++;
                if (index >= limit) {
                    break;
                }
            }
            long lastSequence = store.sequenceOf(page.get(page.size() - 1).auditId());
            if (lastSequence <= cursor) {
                // The cursor must advance or the loop is infinite. This should be unreachable.
                log.error("audit chain cursor stalled at sequence {} for merchant {}", cursor, chainKey);
                break;
            }
            cursor = lastSequence;
        }

        boolean truncated = index >= limit && index < chainLength;
        return ChainVerificationReport.intact(chainKey, index, chainLength, truncated, startedAt);
    }

    /**
     * Verify several merchant chains and return only the ones that failed.
     *
     * <p>An empty result is the answer everyone wants: nothing in the platform's history has been
     * altered.
     */
    public List<ChainVerificationReport> verifyAll(int maxMerchants) {
        List<ChainVerificationReport> broken = new ArrayList<>();
        for (String merchantId : store.findChainKeys(Math.max(1, maxMerchants))) {
            ChainVerificationReport report = verify(merchantId);
            if (!report.intact()) {
                broken.add(report);
            }
        }
        return List.copyOf(broken);
    }

    /**
     * The two checks, applied to one entry.
     *
     * @return the divergence, or null when the entry verifies
     */
    private ChainDivergence check(String chainKey, AuditEvent event, int index, String expectedPrevious) {
        if (!Objects.equals(expectedPrevious, event.previousHash())) {
            return ChainDivergence.brokenLink(chainKey, event.auditId(), index,
                    store.sequenceOf(event.auditId()), expectedPrevious, event.previousHash(),
                    event.occurredAt());
        }
        String recomputed = event.computeHash();
        if (!Objects.equals(recomputed, event.hash())) {
            return ChainDivergence.tampered(chainKey, event.auditId(), index,
                    store.sequenceOf(event.auditId()), recomputed, event.hash(), event.occurredAt());
        }
        return null;
    }

    /** Entries with no merchant belong to the platform's own chain. */
    static String chainKey(String merchantId) {
        return merchantId == null || merchantId.isBlank()
                ? AuditEventStore.PLATFORM_CHAIN : merchantId;
    }
}
