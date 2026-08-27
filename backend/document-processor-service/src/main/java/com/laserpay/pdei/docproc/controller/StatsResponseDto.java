package com.laserpay.pdei.docproc.controller;

import com.laserpay.pdei.docproc.service.DocProcStats;
import com.laserpay.pdei.docproc.service.QuarantineService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response of {@code GET /docproc/v1/stats}: what this worker has done since it started, and
 * which artifacts it could not use.
 *
 * <p>The quarantine list is the interesting half. Counters say a document failed; the quarantine
 * entries say <em>which</em> document, <em>why</em>, and <em>when</em> - which is the difference
 * between an alert and a fix.
 *
 * @param service     module name, so a response copied into a ticket identifies itself
 * @param since       process start; every counter is cumulative from here
 * @param counters    processing counters
 * @param extractors  registered extractors in selection order
 * @param quarantine  most recent quarantine entries, newest first
 */
public record StatsResponseDto(String service,
                               Instant since,
                               Counters counters,
                               List<String> extractors,
                               List<QuarantineEntryDto> quarantine) {

    /** Flattened {@link DocProcStats.Snapshot}. */
    public record Counters(long eventsReceived,
                           long eventsDuplicate,
                           long eventsSelfEmitted,
                           long extracted,
                           long skipped,
                           long quarantined,
                           long failed,
                           long bytesProcessed,
                           long charactersIndexed,
                           long pagesProcessed,
                           long integrityMismatches,
                           Instant lastProcessedAt,
                           Map<String, Long> extractionsByExtractor) {
    }

    /** One quarantine record, rendered for the API. */
    public record QuarantineEntryDto(String evidenceId,
                                     String objectKey,
                                     String reason,
                                     String detail,
                                     Instant at) {

        static QuarantineEntryDto from(QuarantineService.QuarantineEntry entry) {
            return new QuarantineEntryDto(entry.evidenceId(), entry.objectKey(),
                    entry.reason().name(), entry.detail(), entry.at());
        }
    }

    public static StatsResponseDto of(Instant since,
                                      DocProcStats.Snapshot snapshot,
                                      List<String> extractors,
                                      List<QuarantineService.QuarantineEntry> quarantine) {
        Counters counters = new Counters(
                snapshot.eventsReceived(), snapshot.eventsDuplicate(), snapshot.eventsSelfEmitted(),
                snapshot.extracted(), snapshot.skipped(), snapshot.quarantined(), snapshot.failed(),
                snapshot.bytesProcessed(), snapshot.charactersIndexed(), snapshot.pagesProcessed(),
                snapshot.integrityMismatches(), snapshot.lastProcessedAt(),
                snapshot.extractionsByExtractor());
        return new StatsResponseDto(DocProcStats.SERVICE, since, counters, extractors,
                quarantine.stream().map(QuarantineEntryDto::from).toList());
    }
}
