package com.laserpay.pdei.api.service;

import com.laserpay.pdei.api.dto.FunnelResponse;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.model.FunnelMetrics;
import com.laserpay.pdei.core.spi.CaseRepositoryPort;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /metrics/funnel}: events to candidates to ambiguous to AI to human.
 *
 * <p>The window defaults to the last 7 days when the caller supplies neither bound, which is long
 * enough for the shape to be meaningful on a freshly seeded environment and short enough that the
 * aggregate stays cheap.</p>
 *
 * <p>{@code merchantId} is optional: omitted, the numbers are platform-wide, which is what the
 * observability screen shows when no merchant is selected.</p>
 */
@Service
@Transactional(readOnly = true)
public class FunnelQueryService {

    private static final Duration DEFAULT_WINDOW = Duration.ofDays(7);

    private final CaseRepositoryPort cases;
    private final Clocks clock;

    public FunnelQueryService(CaseRepositoryPort cases, Clocks clock) {
        this.cases = cases;
        this.clock = clock;
    }

    public FunnelResponse funnel(String merchantId, Instant from, Instant to) {
        Instant end = to == null ? clock.now() : to;
        Instant start = from == null ? end.minus(DEFAULT_WINDOW) : from;
        if (start.isAfter(end)) {
            // Swapping is friendlier than a 400 here: the two parameters are trivially transposable
            // in a hand-written URL and the intended window is unambiguous either way.
            Instant swap = start;
            start = end;
            end = swap;
        }
        FunnelMetrics metrics = cases.funnel(blankToNull(merchantId), start, end);
        return FunnelResponse.from(metrics == null
                ? FunnelMetrics.zero(blankToNull(merchantId), start, end) : metrics);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
