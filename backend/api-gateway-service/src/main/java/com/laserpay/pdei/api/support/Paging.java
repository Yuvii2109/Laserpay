package com.laserpay.pdei.api.support;

import com.laserpay.pdei.common.error.ValidationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * One place where {@code page} / {@code size} query parameters become a {@link Pageable}.
 *
 * <p>A negative page or a non-positive size is a client mistake and is rejected with the shared
 * {@link ValidationException} (HTTP 400) rather than silently corrected, so a broken client sees the
 * problem instead of quietly receiving page 0 forever. An oversized page is clamped instead of
 * rejected: asking for too much is not an error, it is just capped.</p>
 */
public final class Paging {

    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 200;

    private Paging() {
    }

    /** Validate and clamp, returning the effective size. */
    public static int size(int size, int maxSize) {
        if (size <= 0) {
            throw ValidationException.field("size", "must be greater than zero");
        }
        return Math.min(size, maxSize <= 0 ? MAX_SIZE : maxSize);
    }

    /** Validate a zero-based page index. */
    public static int page(int page) {
        if (page < 0) {
            throw ValidationException.field("page", "must not be negative");
        }
        return page;
    }

    public static Pageable of(int page, int size, int maxSize) {
        return PageRequest.of(page(page), size(size, maxSize));
    }

    public static Pageable of(int page, int size, int maxSize, Sort sort) {
        return PageRequest.of(page(page), size(size, maxSize), sort);
    }
}
