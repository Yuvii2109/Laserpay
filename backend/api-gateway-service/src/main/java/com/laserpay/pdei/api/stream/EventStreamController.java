package com.laserpay.pdei.api.stream;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The two Server-Sent Events streams of PLATFORM-CONTRACT.md section 8.1.
 *
 * <pre>
 * SSE /api/v1/stream/events?merchantId=...   canonical event tail
 * SSE /api/v1/stream/cases/{caseId}          case progress
 * </pre>
 *
 * <p>SSE sits beside the WebSocket rather than replacing it because the two are used differently.
 * The control tower keeps one long-lived socket for the whole dashboard; these streams are opened by
 * a single page for a single subject and closed when the operator navigates away. SSE is one-way,
 * reconnects on its own in every browser, and survives proxies that mangle WebSocket upgrades, which
 * makes it the better fit for a page-scoped tail.</p>
 *
 * <p>Both routes are GET and both are read-only. Frames are pushed by {@link StreamHub}; nothing a
 * client does on these endpoints can change any state.</p>
 */
@RestController
@RequestMapping("/api/v1/stream")
@Tag(name = "stream", description = "Server-Sent Events streams")
public class EventStreamController {

    private final StreamHub hub;

    public EventStreamController(StreamHub hub) {
        this.hub = hub;
    }

    /**
     * The canonical event tail for one merchant.
     *
     * <p>The emitter is returned immediately and the container keeps the request open; the first
     * frame is a HEARTBEAT so the browser's {@code onopen} fires without waiting for real activity.</p>
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Canonical event tail for one merchant",
            description = "Server-Sent Events. Each message is a JSON frame using the contract "
                    + "section 8.1 envelope: {type, at, merchantId, data}.")
    public SseEmitter events(@RequestParam("merchantId") String merchantId) {
        return hub.subscribeToEvents(merchantId);
    }

    /**
     * Progress of one case: every CASE frame that names this case, plus heartbeats.
     *
     * <p>Not scoped by merchant, because the case id already is: a case belongs to exactly one
     * merchant, and the frames delivered here are the ones that carry this case id.</p>
     */
    @GetMapping(value = "/cases/{caseId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Progress stream for one dispute case",
            description = "Server-Sent Events. Emits CASE_UPDATED frames for this case and periodic "
                    + "HEARTBEAT frames.")
    public SseEmitter caseProgress(@PathVariable("caseId") String caseId) {
        return hub.subscribeToCase(caseId);
    }
}
