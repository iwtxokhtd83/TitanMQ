package com.titanmq.store;

import com.titanmq.common.TopicPartition;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages consumer group offsets.
 * Tracks committed offsets per consumer group per topic-partition.
 */
public class OffsetStore {

    // Key: groupId + topicPartition -> committed offset
    private final ConcurrentHashMap<String, Long> committedOffsets = new ConcurrentHashMap<>();

    public void commitOffset(String groupId, TopicPartition tp, long offset) {
        committedOffsets.put(key(groupId, tp), offset);
    }

    public long getCommittedOffset(String groupId, TopicPartition tp) {
        return committedOffsets.getOrDefault(key(groupId, tp), 0L);
    }

    private String key(String groupId, TopicPartition tp) {
        return groupId + ":" + tp.topic() + ":" + tp.partition();
    }
}
