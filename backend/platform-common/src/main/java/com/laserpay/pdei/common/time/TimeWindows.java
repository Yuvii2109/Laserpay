package com.laserpay.pdei.common.time;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Instant-window arithmetic used by expiry, readiness and deadline logic
 * (docs/SHARED-LIBRARY-API.md section 1.9).
 *
 * <p>Everything is UTC and {@link Instant}-based. Null inputs answer {@code false} rather than
 * throwing: an evidence item with no expiry date is simply never expiring, and a dispute with no
 * deadline is never urgent.
 */
public final class TimeWindows {

    /** Warning window for evidence expiry, per PLATFORM-CONTRACT section 7 (-5 score penalty). */
    public static final int EXPIRING_SOON_DAYS = 7;

    /** Deadline urgency threshold for AI admission control (PLATFORM-CONTRACT section 9.4). */
    public static final int DEADLINE_URGENT_HOURS = 48;

    private TimeWindows() {
    }

    /**
     * Whether {@code a} and {@code b} are within {@code days} of each other, in either direction.
     *
     * @throws IllegalArgumentException when {@code days} is negative
     */
    public static boolean withinDays(Instant a, Instant b, int days) {
        if (days < 0) {
            throw new IllegalArgumentException("days must not be negative: " + days);
        }
        if (a == null || b == null) {
            return false;
        }
        return Duration.between(a, b).abs().compareTo(Duration.ofDays(days)) <= 0;
    }

    /** True when {@code expiresAt} is at or before {@code now}. */
    public static boolean isExpired(Instant expiresAt, Instant now) {
        return expiresAt != null && now != null && !expiresAt.isAfter(now);
    }

    /**
     * True when {@code expiresAt} is still in the future but inside the warning window.
     * Mutually exclusive with {@link #isExpired(Instant, Instant)}.
     */
    public static boolean isExpiringSoon(Instant expiresAt, Instant now, int days) {
        if (expiresAt == null || now == null || isExpired(expiresAt, now)) {
            return false;
        }
        return withinDays(now, expiresAt, days);
    }

    /** {@link #isExpiringSoon(Instant, Instant, int)} with the default 7-day window. */
    public static boolean isExpiringSoon(Instant expiresAt, Instant now) {
        return isExpiringSoon(expiresAt, now, EXPIRING_SOON_DAYS);
    }

    /** True when a dispute deadline is inside the admission-control urgency window. */
    public static boolean isDeadlineUrgent(Instant deadlineAt, Instant now) {
        if (deadlineAt == null || now == null || !deadlineAt.isAfter(now)) {
            return false;
        }
        return Duration.between(now, deadlineAt).compareTo(Duration.ofHours(DEADLINE_URGENT_HOURS)) <= 0;
    }

    /** Absolute whole days between two instants; 0 when either is null. */
    public static long daysBetween(Instant a, Instant b) {
        if (a == null || b == null) {
            return 0L;
        }
        return Math.abs(ChronoUnit.DAYS.between(a, b));
    }

    /** Hours remaining until {@code deadlineAt}; 0 once the deadline has passed. */
    public static long hoursUntil(Instant deadlineAt, Instant now) {
        if (deadlineAt == null || now == null || !deadlineAt.isAfter(now)) {
            return 0L;
        }
        return ChronoUnit.HOURS.between(now, deadlineAt);
    }

    /** Start of the UTC day containing {@code i}; used by the nightly expiry sweep. */
    public static Instant startOfDayUtc(Instant i) {
        return i == null ? null : i.truncatedTo(ChronoUnit.DAYS);
    }
}
