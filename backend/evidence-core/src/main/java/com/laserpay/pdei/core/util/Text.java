package com.laserpay.pdei.core.util;

import java.text.Normalizer;
import java.util.Locale;

/** Deterministic text normalisation used by contradiction detection and full-text search. */
public final class Text {

    private Text() {
    }

    /**
     * Normalise a postal address for equality comparison: strip accents, lower case, drop
     * punctuation, collapse whitespace and expand the handful of abbreviations that otherwise cause
     * false "address mismatch" contradictions.
     */
    public static String normalizeAddress(String raw) {
        if (raw == null) {
            return "";
        }
        String value = Normalizer.normalize(raw, Normalizer.Form.NFKD)
                .replaceAll("\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\s+", " ")
                .trim();
        value = " " + value + " ";
        value = value
                .replace(" street ", " st ")
                .replace(" road ", " rd ")
                .replace(" avenue ", " ave ")
                .replace(" apartment ", " apt ")
                .replace(" floor ", " flr ")
                .replace(" building ", " bldg ")
                .replace(" number ", " no ")
                .replace(" nagar ", " ngr ")
                .replace(" post office ", " po ");
        return value.replaceAll("\s+", " ").trim();
    }

    /** True when two addresses are the same place after normalisation. */
    public static boolean sameAddress(String left, String right) {
        String a = normalizeAddress(left);
        String b = normalizeAddress(right);
        if (a.isEmpty() || b.isEmpty()) {
            // Missing data is a provenance gap, not a contradiction.
            return true;
        }
        return a.equals(b);
    }

    /**
     * Build a Postgres {@code tsquery} string from raw user input. Tokens are AND-ed and the last
     * token is turned into a prefix match so type-ahead search behaves.
     */
    public static String toTsQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String[] tokens = raw.toLowerCase(Locale.ROOT).split("[^\p{Alnum}]+");
        StringBuilder query = new StringBuilder();
        String last = null;
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            if (last != null) {
                query.append(last).append(" & ");
            }
            last = token;
        }
        if (last == null) {
            return "";
        }
        return query.append(last).append(":*").toString();
    }

    /** Truncate for summaries and log lines without ever throwing. */
    public static String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 3)) + "...";
    }

    public static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
