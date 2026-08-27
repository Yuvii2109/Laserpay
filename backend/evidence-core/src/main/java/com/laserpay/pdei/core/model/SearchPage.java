package com.laserpay.pdei.core.model;

import java.util.List;

/** Minimal page envelope returned by {@code core.search.EvidenceSearchService}. */
public record SearchPage<T>(List<T> items, int page, int size, long total) {

    public SearchPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> SearchPage<T> empty(int page, int size) {
        return new SearchPage<>(List.of(), page, size, 0L);
    }

    public int totalPages() {
        return size <= 0 ? 0 : (int) Math.ceil(total / (double) size);
    }
}
