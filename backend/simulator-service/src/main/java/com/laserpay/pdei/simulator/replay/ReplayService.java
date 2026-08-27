package com.laserpay.pdei.simulator.replay;

import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.simulator.config.SimulatorProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Re-consumes a topic from an offset or a timestamp, optionally re-publishing what it reads.
 *
 * <h2>Why this exists</h2>
 * Rule 9 says every consumer tolerates duplicates and rule 10 says every consumer assumes late
 * and out-of-order events. Those are assertions until something actually replays a range of
 * history into a live system and the resulting state is shown to be unchanged. That is what this
 * service does, and it is also the recovery procedure for a real normalisation bug: fix the
 * mapping, replay {@code pdei.raw.events.v1} from the bad offset, and the corrected canonical
 * stream is rebuilt without asking any source system for anything.
 *
 * <h2>Design</h2>
 * A raw {@link KafkaConsumer} with {@code assign} + {@code seek}, never {@code subscribe}. Two
 * consequences, both deliberate:
 * <ul>
 *   <li>A throwaway group id per replay, so a replay can never move a live consumer group's
 *       committed offsets. Reading history must not disturb the present.</li>
 *   <li>Explicit control of the starting position on every partition, including
 *       {@code offsetsForTimes} for the timestamp case.</li>
 * </ul>
 * Auto-commit is off and nothing is ever committed. The bookmark written to
 * {@code pdei:stream:offsets:{consumerGroup}} (platform contract 12) is a record of where the
 * replay reached, for the operator - not a Kafka offset commit.
 */
@Service
public class ReplayService {

    /** Platform contract 12: {@code pdei:stream:offsets:{consumerGroup}}. */
    public static final String BOOKMARK_KEY_PREFIX = "pdei:stream:offsets:";
    private static final String METRIC_REPLAYED = "pdei_sim_replayed_records_total";
    private static final int EMPTY_POLLS_BEFORE_STOP = 2;

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final KafkaProperties kafkaProperties;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectProvider<StringRedisTemplate> redisTemplates;
    private final SimulatorProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clocks clock;

    public ReplayService(KafkaProperties kafkaProperties,
                         KafkaTemplate<String, Object> kafkaTemplate,
                         ObjectProvider<StringRedisTemplate> redisTemplates,
                         SimulatorProperties properties,
                         MeterRegistry meterRegistry,
                         Clocks clock) {
        this.kafkaProperties = kafkaProperties;
        this.kafkaTemplate = kafkaTemplate;
        this.redisTemplates = redisTemplates;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /** Runs a replay to completion. Blocking: bounded by {@code maxRecords} and empty polls. */
    public ReplayResult replay(ReplayRequest request) {
        String replayId = "replay-" + UUID.randomUUID();
        String groupId = ConsumerGroups.PDEI_SIMULATOR_SERVICE + "-" + replayId;
        Instant startedAt = clock.now();

        int maxRecords = request.maxRecords() == null || request.maxRecords() <= 0
                ? properties.getReplay().getMaxRecords()
                : Math.min(request.maxRecords(), properties.getReplay().getMaxRecords());
        boolean republish = request.republish() == null
                ? properties.getReplay().isRepublish()
                : request.republish();

        long read = 0;
        long sent = 0;
        long filtered = 0;
        Map<String, Long> startOffsets = new LinkedHashMap<>();
        Map<String, Long> endOffsets = new LinkedHashMap<>();
        List<Integer> partitionNumbers = new ArrayList<>();
        String note;

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerConfig(groupId))) {
            List<TopicPartition> partitions = partitionsOf(consumer, request.topic());
            if (partitions.isEmpty()) {
                return new ReplayResult(replayId, request.topic(), groupId, List.of(), Map.of(),
                        Map.of(), 0, 0, 0, republish, startedAt, clock.now(),
                        "topic has no partitions or does not exist");
            }
            consumer.assign(partitions);
            seek(consumer, partitions, request, startOffsets);
            partitions.forEach(partition -> partitionNumbers.add(partition.partition()));

            int emptyPolls = 0;
            Duration pollTimeout = properties.getReplay().getPollTimeout();
            while (read < maxRecords && emptyPolls < EMPTY_POLLS_BEFORE_STOP) {
                ConsumerRecords<String, String> records = consumer.poll(pollTimeout);
                if (records.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                for (ConsumerRecord<String, String> record : records) {
                    if (read >= maxRecords) {
                        break;
                    }
                    read++;
                    endOffsets.put(key(record.topic(), record.partition()), record.offset());
                    if (!matchesMerchant(record, request.merchantId())) {
                        filtered++;
                        continue;
                    }
                    if (republish && republish(record)) {
                        sent++;
                    }
                }
            }
            note = read >= maxRecords
                    ? "stopped at the " + maxRecords + " record ceiling"
                    : "reached the end of the requested range";

        } catch (RuntimeException e) {
            log.error("replay {} of {} failed", replayId, request.topic(), e);
            return new ReplayResult(replayId, request.topic(), groupId, partitionNumbers,
                    startOffsets, endOffsets, read, sent, filtered, republish, startedAt,
                    clock.now(), "failed: " + e);
        }

        writeBookmark(groupId, request.topic(), endOffsets);
        meterRegistry.counter(METRIC_REPLAYED, "topic", request.topic()).increment(read);

        ReplayResult result = new ReplayResult(replayId, request.topic(), groupId, partitionNumbers,
                startOffsets, endOffsets, read, sent, filtered, republish, startedAt, clock.now(),
                note);
        log.info("replay {} of {}: read={} republished={} filtered={} in {} ms ({})",
                replayId, request.topic(), read, sent, filtered, result.durationMillis(), note);
        return result;
    }

    // -------------------------------------------------------------------------------------

    private Map<String, Object> consumerConfig(String groupId) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Never commit: a replay must not move any group's position, including its own.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        return config;
    }

    private static List<TopicPartition> partitionsOf(KafkaConsumer<String, String> consumer, String topic) {
        List<PartitionInfo> infos = consumer.partitionsFor(topic);
        if (infos == null) {
            return List.of();
        }
        List<TopicPartition> partitions = new ArrayList<>(infos.size());
        for (PartitionInfo info : infos) {
            partitions.add(new TopicPartition(info.topic(), info.partition()));
        }
        return partitions;
    }

    /**
     * Positions every assigned partition.
     *
     * <p>The timestamp case uses {@code offsetsForTimes}, which returns the first offset at or
     * after the instant. A partition with nothing after that instant returns null, and the right
     * answer there is to seek to the end - not to the beginning, which would replay the entire
     * partition and is the classic way to turn a targeted replay into an incident.
     */
    private void seek(KafkaConsumer<String, String> consumer, List<TopicPartition> partitions,
                      ReplayRequest request, Map<String, Long> startOffsets) {
        if (request.byOffset()) {
            for (TopicPartition partition : partitions) {
                consumer.seek(partition, request.fromOffset());
                startOffsets.put(key(partition.topic(), partition.partition()), request.fromOffset());
            }
            return;
        }

        Map<TopicPartition, Long> targets = new HashMap<>();
        long epochMillis = request.fromTimestamp().toEpochMilli();
        partitions.forEach(partition -> targets.put(partition, epochMillis));

        Map<TopicPartition, OffsetAndTimestamp> resolved = consumer.offsetsForTimes(targets);
        for (TopicPartition partition : partitions) {
            OffsetAndTimestamp offset = resolved.get(partition);
            if (offset == null) {
                consumer.seekToEnd(List.of(partition));
                startOffsets.put(key(partition.topic(), partition.partition()),
                        consumer.position(partition));
            } else {
                consumer.seek(partition, offset.offset());
                startOffsets.put(key(partition.topic(), partition.partition()), offset.offset());
            }
        }
    }

    /**
     * Re-publishes a record with its key and headers intact.
     *
     * <p>Preserving the key matters: it keeps the replayed copy on the same partition as the
     * original, so the same consumer instance sees both and its {@code eventId} deduplication
     * actually gets the chance to fire.
     */
    private boolean republish(ConsumerRecord<String, String> record) {
        if (record.value() == null) {
            return false; // tombstone: nothing to replay
        }
        try {
            // Parsed back to a tree rather than sent as a raw String: the producer's value
            // serializer is JsonSerializer, and handing it a String would publish a
            // JSON-encoded string literal instead of the original object.
            ProducerRecord<String, Object> outbound = new ProducerRecord<>(
                    record.topic(), record.key(), Json.readTree(record.value()));
            for (Header header : record.headers()) {
                outbound.headers().add(header.key(), header.value());
            }
            kafkaTemplate.send(outbound);
            return true;
        } catch (RuntimeException e) {
            log.warn("could not re-publish {}-{}@{}: {}", record.topic(), record.partition(),
                    record.offset(), e.toString());
            return false;
        }
    }

    private static boolean matchesMerchant(ConsumerRecord<String, String> record, String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            return true;
        }
        Header header = record.headers().lastHeader(EventHeaders.MERCHANT_ID);
        if (header != null && header.value() != null) {
            return merchantId.equals(new String(header.value(), StandardCharsets.UTF_8));
        }
        // No header: fall back to the body. Slower, but a replay filtered to the wrong merchant is
        // worse than a replay that had to parse some JSON.
        String value = record.value();
        return value != null && value.contains("\"" + merchantId + "\"");
    }

    private void writeBookmark(String groupId, String topic, Map<String, Long> endOffsets) {
        StringRedisTemplate redis = redisTemplates.getIfAvailable();
        if (redis == null || endOffsets.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> bookmark = new LinkedHashMap<>();
            bookmark.put("topic", topic);
            bookmark.put("at", clock.now().toString());
            bookmark.put("offsets", endOffsets);
            redis.opsForValue().set(BOOKMARK_KEY_PREFIX + groupId, Json.write(bookmark),
                    properties.getRuns().getRedisTtl());
        } catch (RuntimeException e) {
            log.debug("could not write replay bookmark for {}: {}", groupId, e.toString());
        }
    }

    private static String key(String topic, int partition) {
        return topic + "-" + partition;
    }
}
