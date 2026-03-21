package com.titanmq.common;

/**
 * Represents a specific partition within a topic.
 */
public record TopicPartition(String topic, int partition) {

    public TopicPartition {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be null or blank");
        }
        if (partition < 0) {
            throw new IllegalArgumentException("partition must be >= 0");
        }
    }

    @Override
    public String toString() {
        return topic + "-" + partition;
    }
}
