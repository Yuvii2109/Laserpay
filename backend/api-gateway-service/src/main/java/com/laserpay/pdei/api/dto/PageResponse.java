package com.laserpay.pdei.api.dto;

import com.laserpay.pdei.core.model.SearchPage;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * The single pagination envelope for every list route of PLATFORM-CONTRACT.md section 8.1.
 *
 * <p>Field names are fixed: {@code content}, {@code page}, {@code size}, {@code totalElements},
 * {@code totalPages}. {@code page} is zero-based.</p>
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public PageResponse {
        content = content == null ? List.of() : List.copyOf(content);
    }

    /** Build from a raw slice plus the total the data source reported. */
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int effectiveSize = size <= 0 ? (content == null || content.isEmpty() ? 1 : content.size()) : size;
        int totalPages = effectiveSize <= 0 ? 0 : (int) Math.ceil(totalElements / (double) effectiveSize);
        return new PageResponse<>(content, Math.max(0, page), size, totalElements, totalPages);
    }

    /** Build from a Spring Data page, mapping each element on the way out. */
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    /** Build from an evidence-core {@link SearchPage}, mapping each element on the way out. */
    public static <E, T> PageResponse<T> of(SearchPage<E> page, Function<E, T> mapper) {
        return of(page.items().stream().map(mapper).toList(), page.page(), page.size(), page.total());
    }

    /**
     * Build from a slice returned by a repository port that has no count query.
     *
     * <p>The ports in {@code evidence-core} return a bounded {@code List} rather than a page, so the
     * total is not knowable without a second query. The honest answer is "at least this many": the
     * total is the offset consumed so far plus what came back, and a full page implies there may be
     * more. Callers that need an exact total must use a Spring Data {@code Page}.</p>
     */
    public static <T> PageResponse<T> ofSlice(List<T> slice, int page, int size) {
        List<T> safe = slice == null ? List.of() : slice;
        long consumed = (long) Math.max(0, page) * Math.max(1, size);
        long atLeast = consumed + safe.size();
        int totalPages = safe.size() < size ? Math.max(0, page) + 1 : Math.max(0, page) + 2;
        if (safe.isEmpty() && page == 0) {
            totalPages = 0;
        }
        return new PageResponse<>(safe, Math.max(0, page), size, atLeast, totalPages);
    }
}
