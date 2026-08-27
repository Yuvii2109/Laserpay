package com.laserpay.pdei.core.spi.jdbc;

import com.laserpay.pdei.common.money.Money;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared conversions for the JDBC adapters.
 *
 * <p>Two rules are enforced here rather than repeated in every row mapper:</p>
 * <ul>
 *   <li>timestamps are read as {@code TIMESTAMPTZ} into {@link Instant} - never {@code LocalDateTime};</li>
 *   <li>money is read as {@code (BIGINT amount_minor, CHAR(3) currency)} into {@link Money} - never
 *       through a floating point or decimal type.</li>
 * </ul>
 */
final class JdbcSupport {

    static final String SCHEMA = "pdei";

    private JdbcSupport() {
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    static Money money(ResultSet rs, String amountColumn, String currencyColumn) throws SQLException {
        long amountMinor = rs.getLong(amountColumn);
        if (rs.wasNull()) {
            return null;
        }
        String currency = rs.getString(currencyColumn);
        return currency == null ? null : Money.of(amountMinor, currency);
    }

    static <E extends Enum<E>> E enumValue(ResultSet rs, String column, Class<E> type) throws SQLException {
        String value = rs.getString(column);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim());
        } catch (IllegalArgumentException e) {
            // An unknown enum value means a newer writer; degrade to null rather than failing a read.
            return null;
        }
    }

    static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /** Enum sets are stored as comma-separated names: readable in psql, no array type coupling. */
    static <E extends Enum<E>> Set<E> enumSet(String csv, Class<E> type) {
        if (csv == null || csv.isBlank()) {
            return EnumSet.noneOf(type);
        }
        Set<E> values = new LinkedHashSet<>();
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                values.add(Enum.valueOf(type, trimmed));
            } catch (IllegalArgumentException ignored) {
                // skip unknown members
            }
        }
        return values;
    }

    static String csv(Set<? extends Enum<?>> values) {
        return values == null ? "" : values.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of()
                : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    static int offset(int page, int size) {
        return Math.max(0, page) * Math.max(1, size);
    }
}
