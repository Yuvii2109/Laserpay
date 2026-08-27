package com.laserpay.pdei.docproc.extract;

import java.text.Normalizer;

/**
 * Whitespace and unicode clean-up applied to every extracted document before it reaches the
 * Postgres {@code tsvector}.
 *
 * <p>Why this exists: PDF text layers arrive full of soft hyphens, non-breaking spaces, form
 * feeds and per-glyph line breaks. Left alone they produce tokens like {@code deliv} +
 * {@code ered} in the FTS index, and a merchant searching for "delivered" finds nothing. The
 * normalisation is intentionally conservative - it never drops characters that could carry
 * meaning (digits, currency symbols, punctuation inside identifiers).
 */
public final class TextNormalizer {

    /** U+00AD SOFT HYPHEN: invisible in a viewer, token-splitting in an FTS index. */
    private static final char SOFT_HYPHEN = '­';
    /** U+00A0 NO-BREAK SPACE. */
    private static final char NBSP = ' ';
    /** U+2007 FIGURE SPACE, common in PDF-generated invoice tables. */
    private static final char FIGURE_SPACE = ' ';
    /** U+202F NARROW NO-BREAK SPACE. */
    private static final char NARROW_NBSP = ' ';

    private TextNormalizer() {
    }

    /**
     * Normalise extracted text: NFKC unicode form, control characters removed, de-hyphenate
     * line-broken words, collapse runs of whitespace, trim.
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = Normalizer.normalize(raw, Normalizer.Form.NFKC);

        StringBuilder cleaned = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == SOFT_HYPHEN) {
                continue;
            }
            if (c == NBSP || c == FIGURE_SPACE || c == NARROW_NBSP) {
                cleaned.append(' ');
                continue;
            }
            if (c == '\n' || c == '\r' || c == '\t') {
                cleaned.append(c);
                continue;
            }
            if (Character.isISOControl(c)) {
                cleaned.append(' '); // form feeds, vertical tabs, stray NULs
                continue;
            }
            cleaned.append(c);
        }

        String result = cleaned.toString();
        // "deliv-\nered" -> "delivered": hyphen plus line break inside a word.
        result = result.replaceAll("(?<=\\p{L})-\\s*\\R\\s*(?=\\p{L})", "");
        // Normalise every line ending, then collapse blank-line runs to a single break.
        result = result.replaceAll("\\R", "\n");
        result = result.replaceAll("[ \\t]+", " ");
        result = result.replaceAll(" *\n *", "\n");
        result = result.replaceAll("\n{3,}", "\n\n");
        return result.strip();
    }

    /**
     * Truncate at a character ceiling on a whitespace boundary where possible, so the tail of the
     * FTS text is never half a token.
     *
     * @return the possibly shortened text; never null
     */
    public static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        int cut = text.lastIndexOf(' ', maxChars);
        if (cut < maxChars / 2) {
            cut = maxChars;
        }
        return text.substring(0, cut).stripTrailing();
    }
}
