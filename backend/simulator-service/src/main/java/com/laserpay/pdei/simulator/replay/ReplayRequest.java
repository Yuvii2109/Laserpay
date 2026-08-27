package com.laserpay.pdei.simulator.replay;

import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.kafka.Topics;

import java.time.Instant;

/**
 * Body of {@code POST /sim/v1/replay} (platform contract 8.5):
 * {@code {topic, fromOffset|fromTimestamp, merchantId?}}.
 *
 * <p>Exactly one starting point must be given. Offset is precise and is what you use to replay a
 * known bad range; timestamp is what you use when all you know is "since about nine this
 * morning", and Kafka resolves it to the first offset at or after that instant on each partition.
 *
 * @param topic         topic to re-consume; must be a PDEI topic
 * @param fromOffset    offset to start from on every partition
 * @param fromTimestamp instant to start from, resolved per partition
 * @param merchantId    optional filter, matched on the {@code pdei-merchant-id} header
 * @param maxRecords    ceiling on records read, defaulted from configuration when null
 * @param republish     re-publish what is read; defaults to the configured value when null
 */
public record ReplayRequest(String topic,
                            Long fromOffset,
                            Instant fromTimestamp,
                            String merchantId,
                            Integer maxRecords,
                            Boolean republish) {

    public ReplayRequest {
        if (topic == null || topic.isBlank()) {
            throw new ValidationException("topic is required");
        }
        topic = topic.strip();
        if (!Topics.ALL.contains(topic)) {
            throw new ValidationException("unknown PDEI topic: " + topic);
        }
        if (fromOffset == null && fromTimestamp == null) {
            throw new ValidationException("one of fromOffset or fromTimestamp is required");
        }
        if (fromOffset != null && fromTimestamp != null) {
            throw new ValidationException("fromOffset and fromTimestamp are mutually exclusive");
        }
        if (fromOffset != null && fromOffset < 0) {
            throw new ValidationException("fromOffset must not be negative");
        }
    }

    public boolean byOffset() {
        return fromOffset != null;
    }
}
