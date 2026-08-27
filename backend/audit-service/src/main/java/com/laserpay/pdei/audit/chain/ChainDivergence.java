package com.laserpay.pdei.audit.chain;

import java.time.Instant;

/**
 * The first point at which a merchant's audit chain stops verifying.
 *
 * <p>Deliberately precise about <em>which</em> hash disagreed with <em>what</em>, because the two
 * failure modes have completely different meanings:
 *
 * <ul>
 *   <li>{@link Kind#TAMPERED_CONTENT} - the entry's own hash no longer matches its content. Someone
 *       edited a stored row. The chain structure is intact; the row is not.</li>
 *   <li>{@link Kind#BROKEN_LINK} - the entry's {@code previousHash} does not point at the hash of
 *       the entry before it. Either a row was deleted, or a row was inserted, or two writers forked
 *       the chain.</li>
 * </ul>
 *
 * @param auditId      the entry that failed
 * @param index        zero-based position in the walked chain
 * @param sequenceNo   the database's own {@code sequence_no} for that entry, or -1 if unknown
 * @param expectedHash what verification computed: the recomputed content hash (TAMPERED_CONTENT) or
 *                     the hash of the preceding entry (BROKEN_LINK)
 * @param actualHash   what was stored: the row's {@code hash} (TAMPERED_CONTENT) or its
 *                     {@code previous_hash} (BROKEN_LINK)
 */
public record ChainDivergence(
        String merchantId,
        String auditId,
        int index,
        long sequenceNo,
        Kind kind,
        String expectedHash,
        String actualHash,
        String detail,
        Instant entryOccurredAt) {

    public enum Kind {
        /** The stored hash does not match a recomputation over the entry's own fields. */
        TAMPERED_CONTENT,
        /** The entry does not follow its predecessor: a row was removed, inserted or forked. */
        BROKEN_LINK
    }

    public static ChainDivergence tampered(String merchantId, String auditId, int index, long sequenceNo,
                                           String recomputedHash, String storedHash, Instant occurredAt) {
        return new ChainDivergence(merchantId, auditId, index, sequenceNo, Kind.TAMPERED_CONTENT,
                recomputedHash, storedHash,
                "entry content was altered after it was written: recomputing its fields yields "
                        + shorten(recomputedHash) + " but the stored hash is " + shorten(storedHash),
                occurredAt);
    }

    public static ChainDivergence brokenLink(String merchantId, String auditId, int index, long sequenceNo,
                                             String expectedPrevious, String storedPrevious,
                                             Instant occurredAt) {
        return new ChainDivergence(merchantId, auditId, index, sequenceNo, Kind.BROKEN_LINK,
                expectedPrevious, storedPrevious,
                "entry does not follow its predecessor: expected previousHash "
                        + shorten(expectedPrevious) + " but found " + shorten(storedPrevious)
                        + " (a row was deleted, inserted, or the chain forked)",
                occurredAt);
    }

    /** First 12 characters are plenty to identify a hash in a message; the full values are fields. */
    private static String shorten(String hash) {
        if (hash == null) {
            return "null";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12) + "...";
    }
}
