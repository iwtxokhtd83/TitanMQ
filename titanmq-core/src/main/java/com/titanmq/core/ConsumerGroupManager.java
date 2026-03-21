package com.titanmq.core;

import com.titanmq.common.TopicPartition;
import com.titanmq.store.OffsetStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages consumer groups and partition assignment.
 *
 * <p>Supports multiple rebalancing strategies:
 * <ul>
 *   <li>Range: Assigns contiguous partitions to consumers</li>
 *   <li>RoundRobin: Distributes partitions evenly across consumers</li>
 *   <li>Sticky: Minimizes partition movement during rebalance</li>
 * </ul>
 */
public class ConsumerGroupManager {

    private static final Logger log = LoggerFactory.getLogger(ConsumerGroupManager.class);

    private final OffsetStore offsetStore;
    private final ConcurrentHashMap<String, ConsumerGroup> groups = new ConcurrentHashMap<>();

    public ConsumerGroupManager(OffsetStore offsetStore) {
        this.offsetStore = offsetStore;
    }

    public void joinGroup(String groupId, String consumerId, List<String> topics) {
        ConsumerGroup group = groups.computeIfAbsent(groupId, ConsumerGroup::new);
        group.addMember(consumerId, topics);
        log.info("Consumer {} joined group {}", consumerId, groupId);
    }

    public void leaveGroup(String groupId, String consumerId) {
        ConsumerGroup group = groups.get(groupId);
        if (group != null) {
            group.removeMember(consumerId);
            log.info("Consumer {} left group {}", consumerId, groupId);
        }
    }

    public Map<String, List<TopicPartition>> rebalance(String groupId, Map<String, Integer> topicPartitionCounts) {
        ConsumerGroup group = groups.get(groupId);
        if (group == null) return Map.of();
        return group.rebalance(topicPartitionCounts);
    }

    public void commitOffset(String groupId, TopicPartition tp, long offset) {
        offsetStore.commitOffset(groupId, tp, offset);
    }

    public long getCommittedOffset(String groupId, TopicPartition tp) {
        return offsetStore.getCommittedOffset(groupId, tp);
    }

    /**
     * Internal representation of a consumer group.
     */
    static class ConsumerGroup {
        private final String groupId;
        private final Map<String, List<String>> members = new LinkedHashMap<>(); // consumerId -> subscribed topics

        ConsumerGroup(String groupId) {
            this.groupId = groupId;
        }

        void addMember(String consumerId, List<String> topics) {
            members.put(consumerId, topics);
        }

        void removeMember(String consumerId) {
            members.remove(consumerId);
        }

        /**
         * Round-robin partition assignment across group members.
         */
        Map<String, List<TopicPartition>> rebalance(Map<String, Integer> topicPartitionCounts) {
            Map<String, List<TopicPartition>> assignment = new HashMap<>();
            List<String> memberIds = new ArrayList<>(members.keySet());
            if (memberIds.isEmpty()) return assignment;

            for (String memberId : memberIds) {
                assignment.put(memberId, new ArrayList<>());
            }

            // Collect all partitions for subscribed topics
            List<TopicPartition> allPartitions = new ArrayList<>();
            Set<String> allTopics = new HashSet<>();
            members.values().forEach(allTopics::addAll);

            for (String topic : allTopics) {
                int count = topicPartitionCounts.getOrDefault(topic, 0);
                for (int p = 0; p < count; p++) {
                    allPartitions.add(new TopicPartition(topic, p));
                }
            }

            // Round-robin assignment
            for (int i = 0; i < allPartitions.size(); i++) {
                String memberId = memberIds.get(i % memberIds.size());
                assignment.get(memberId).add(allPartitions.get(i));
            }

            log.info("Rebalanced group {}: {}", groupId, assignment);
            return assignment;
        }
    }
}
