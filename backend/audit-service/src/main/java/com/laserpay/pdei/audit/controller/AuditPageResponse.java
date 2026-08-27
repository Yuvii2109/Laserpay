package com.laserpay.pdei.audit.controller;

import java.util.List;

/**
 * One page of audit entries.
 *
 * @param total entries matching the filter, ignoring paging - a client walking a chain needs to
 *              know when it has seen all of it
 */
public record AuditPageResponse(
        List<AuditEventResponse> items,
        int page,
        int size,
        long total) {

    public AuditPageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean hasMore() {
        return (long) (page + 1) * size < total;
    }
}
