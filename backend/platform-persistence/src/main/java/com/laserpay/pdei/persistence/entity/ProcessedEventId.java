package com.laserpay.pdei.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key of {@code pdei.processed_events}: one row per (event, consumer group).
 *
 * <p>This is the only entity in the module whose key is not a single prefixed string, because
 * the contract mandates the composite primary key {@code (event_id, consumer_group)}.
 */
@Embeddable
public class ProcessedEventId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "consumer_group", nullable = false, length = 128)
    private String consumerGroup;

    public ProcessedEventId() {
    }

    public ProcessedEventId(String eventId, String consumerGroup) {
        this.eventId = eventId;
        this.consumerGroup = consumerGroup;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProcessedEventId other)) {
            return false;
        }
        return Objects.equals(eventId, other.eventId)
                && Objects.equals(consumerGroup, other.consumerGroup);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, consumerGroup);
    }

    @Override
    public String toString() {
        return eventId + "@" + consumerGroup;
    }
}
