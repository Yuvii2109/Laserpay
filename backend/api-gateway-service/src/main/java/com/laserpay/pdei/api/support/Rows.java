package com.laserpay.pdei.api.support;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the {@code List<Object[]>} that native aggregate queries return into typed maps.
 *
 * <p>The distribution queries in platform-persistence ({@code readinessBandDistribution},
 * {@code countByStatus}, {@code countByTypeAndStatus}) are native, so the driver decides the Java
 * type of each column: an enum column arrives as a {@code String}, and a {@code count(*)} arrives as
 * {@code Long} on one driver version and {@code BigInteger} on another. Coercing in one place keeps
 * that detail out of every service that reads a distribution.</p>
 *
 * <p>An unrecognised enum name is skipped rather than thrown: a KPI panel should not fail to render
 * because the database holds one row written by a newer version of the platform.</p>
 */
public final class Rows {

    private Rows() {
    }

    /** {@code [enumName, count]} rows into an {@code EnumMap}, with every constant present. */
    public static <E extends Enum<E>> Map<E, Long> toEnumCounts(List<Object[]> rows, Class<E> type) {
        Map<E, Long> counts = new EnumMap<>(type);
        for (E constant : type.getEnumConstants()) {
            counts.put(constant, 0L);
        }
        if (rows == null) {
            return counts;
        }
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || row[0] == null) {
                continue;
            }
            E key = parseEnum(type, String.valueOf(row[0]));
            if (key != null) {
                counts.merge(key, asLong(row[1]), Long::sum);
            }
        }
        return counts;
    }

    /**
     * {@code [enumA, enumB, count]} rows collapsed onto the second column.
     *
     * <p>Used for {@code countByTypeAndStatus}, where the summary wants totals per status and the
     * type column is only there for the other caller.</p>
     */
    public static <E extends Enum<E>> Map<E, Long> toEnumCountsFromSecondColumn(List<Object[]> rows,
                                                                                Class<E> type) {
        Map<E, Long> counts = new EnumMap<>(type);
        for (E constant : type.getEnumConstants()) {
            counts.put(constant, 0L);
        }
        if (rows == null) {
            return counts;
        }
        for (Object[] row : rows) {
            if (row == null || row.length < 3 || row[1] == null) {
                continue;
            }
            E key = parseEnum(type, String.valueOf(row[1]));
            if (key != null) {
                counts.merge(key, asLong(row[2]), Long::sum);
            }
        }
        return counts;
    }

    public static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
