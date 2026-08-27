package com.laserpay.pdei.common.time;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeWindowsTest {

    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");

    @Test
    void withinDaysIsSymmetricAndInclusiveAtTheBoundary() {
        Instant sevenDaysLater = NOW.plus(Duration.ofDays(7));

        assertThat(TimeWindows.withinDays(NOW, sevenDaysLater, 7)).isTrue();
        assertThat(TimeWindows.withinDays(sevenDaysLater, NOW, 7)).isTrue();
        assertThat(TimeWindows.withinDays(NOW, sevenDaysLater.plusMillis(1), 7)).isFalse();
    }

    @Test
    void withinDaysIsNullSafeAndRejectsNegativeWindows() {
        assertThat(TimeWindows.withinDays(null, NOW, 7)).isFalse();
        assertThat(TimeWindows.withinDays(NOW, null, 7)).isFalse();
        assertThatThrownBy(() -> TimeWindows.withinDays(NOW, NOW, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiryAndExpiringSoonAreMutuallyExclusive() {
        Instant expiredYesterday = NOW.minus(Duration.ofDays(1));
        Instant expiresInThreeDays = NOW.plus(Duration.ofDays(3));
        Instant expiresInThirtyDays = NOW.plus(Duration.ofDays(30));

        assertThat(TimeWindows.isExpired(expiredYesterday, NOW)).isTrue();
        assertThat(TimeWindows.isExpiringSoon(expiredYesterday, NOW)).isFalse();

        assertThat(TimeWindows.isExpired(expiresInThreeDays, NOW)).isFalse();
        assertThat(TimeWindows.isExpiringSoon(expiresInThreeDays, NOW)).isTrue();

        assertThat(TimeWindows.isExpired(expiresInThirtyDays, NOW)).isFalse();
        assertThat(TimeWindows.isExpiringSoon(expiresInThirtyDays, NOW)).isFalse();
    }

    @Test
    void expiryAtExactlyNowCountsAsExpired() {
        assertThat(TimeWindows.isExpired(NOW, NOW)).isTrue();
    }

    @Test
    void evidenceWithoutAnExpiryNeverExpires() {
        assertThat(TimeWindows.isExpired(null, NOW)).isFalse();
        assertThat(TimeWindows.isExpiringSoon(null, NOW)).isFalse();
    }

    @Test
    void deadlineUrgencyUsesTheFortyEightHourAdmissionWindow() {
        assertThat(TimeWindows.isDeadlineUrgent(NOW.plus(Duration.ofHours(47)), NOW)).isTrue();
        assertThat(TimeWindows.isDeadlineUrgent(NOW.plus(Duration.ofHours(48)), NOW)).isTrue();
        assertThat(TimeWindows.isDeadlineUrgent(NOW.plus(Duration.ofHours(49)), NOW)).isFalse();
        // A deadline already passed is not "urgent" - it is a deterministic escalation.
        assertThat(TimeWindows.isDeadlineUrgent(NOW.minusSeconds(1), NOW)).isFalse();
    }

    @Test
    void hoursUntilAndDaysBetween() {
        assertThat(TimeWindows.hoursUntil(NOW.plus(Duration.ofHours(30)), NOW)).isEqualTo(30L);
        assertThat(TimeWindows.hoursUntil(NOW.minusSeconds(1), NOW)).isZero();
        assertThat(TimeWindows.daysBetween(NOW, NOW.plus(Duration.ofDays(9)))).isEqualTo(9L);
        assertThat(TimeWindows.daysBetween(NOW.plus(Duration.ofDays(9)), NOW)).isEqualTo(9L);
        assertThat(TimeWindows.daysBetween(null, NOW)).isZero();
    }

    @Test
    void startOfDayTruncatesInUtc() {
        assertThat(TimeWindows.startOfDayUtc(Instant.parse("2026-08-26T23:59:59.999Z")))
                .isEqualTo(Instant.parse("2026-08-26T00:00:00Z"));
    }

    @Test
    void fixedClockIsTheDeterministicTestingSeam() {
        Clocks clock = Clocks.fixed(NOW);

        assertThat(clock.now()).isEqualTo(NOW);
        assertThat(clock.now()).isEqualTo(clock.now());
        assertThat(clock.toJavaClock().instant()).isEqualTo(NOW);
        assertThat(Clocks.system().now()).isAfter(Instant.EPOCH);
    }
}
