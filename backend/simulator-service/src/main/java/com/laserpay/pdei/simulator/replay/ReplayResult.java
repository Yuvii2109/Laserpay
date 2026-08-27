package com.laserpay.pdei.simulator.replay;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What a replay actually did.
 *
 * <p>The numbers are the proof. "Replayable" is a claim; "read 4,812 records from offsets
 * {@code {0: 1200, 1: 1198, ...}}, re-published 4,812, downstream state unchanged" is a
 * demonstration.
 *
 * @param replayId           id of this replay, for correlating logs
 * @param topic              topic that was re-consumed
 * @param consumerGroup      throwaway group used, so live consumers' offsets are untouched
 * @param partitions         partitions read
 * @param startOffsets       resolved starting offset per partition
 * @param endOffsets         offset reached per partition
 * @param recordsRead        records polled
 * @param recordsRepublished records written back to the topic
 * @param recordsFiltered    records skipped by the merchant filter
 * @param republished        whether re-publication was enabled
 * @param startedAt          when the replay began
 * @param finishedAt         when it ended
 * @param note               human-readable summary, including why it stopped
 */
public record ReplayResult(String replayId,
                           String topic,
                           String consumerGroup,
                           List<Integer> partitions,
                           Map<String, Long> startOffsets,
                           Map<String, Long> endOffsets,
                           long recordsRead,
                           long recordsRepublished,
                           long recordsFiltered,
                           boolean republished,
                           Instant startedAt,
                           Instant finishedAt,
                           String note) {

    public ReplayResult {
        partitions = partitions == null ? List.of() : List.copyOf(partitions);
        startOffsets = startOffsets == null ? Map.of() : Map.copyOf(startOffsets);
        endOffsets = endOffsets == null ? Map.of() : Map.copyOf(endOffsets);
    }

    public long durationMillis() {
        if (startedAt == null || finishedAt == null) {
            return 0L;
        }
        return finishedAt.toEpochMilli() - startedAt.toEpochMilli();
    }
}
