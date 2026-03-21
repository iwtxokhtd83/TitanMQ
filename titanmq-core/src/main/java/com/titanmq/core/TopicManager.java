package com.titanmq.core;

import com.titanmq.common.TitanMessage;
import com.titanmq.common.TopicPartition;
import com.titanmq.common.config.BrokerConfig;
import com.titanmq.store.CommitLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages topics and their partitions.
 * Each topic-partition is backed by its own {@link CommitLog}.
 */
public class TopicManager {

    private static final Logger log = LoggerFactory.getLogger(TopicManager.class);

    private final BrokerConfig config;
    private final ConcurrentHashMap<TopicPartition, CommitLog> logs = new ConcurrentHashMap<>();

    public TopicManager(BrokerConfig config) {
        this.config = config;
    }

    /**
     * Create a topic with the specified number of partitions.
     */
    public void createTopic(String topic, int numPartitions) throws IOException {
        for (int i = 0; i < numPartitions; i++) {
            TopicPartition tp = new TopicPartition(topic, i);
            if (!logs.containsKey(tp)) {
                Path dir = config.dataDir().resolve(topic).resolve("partition-" + i);
                CommitLog commitLog = new CommitLog(dir, config.segmentSizeBytes());
                logs.put(tp, commitLog);
                log.info("Created partition {}", tp);
            }
        }
    }

    /**
     * Append a message to the appropriate partition.
     * Uses key-based partitioning if a key is present, otherwise round-robin.
     */
    public long append(TitanMessage message) throws IOException {
        int partition = resolvePartition(message);
        TopicPartition tp = new TopicPartition(message.topic(), partition);
        CommitLog commitLog = logs.get(tp);
        if (commitLog == null) {
            // Auto-create topic with default partitions
            createTopic(message.topic(), config.numPartitions());
            commitLog = logs.get(tp);
        }
        TitanMessage enriched = TitanMessage.builder()
                .id(message.id())
                .topic(message.topic())
                .partition(partition)
                .timestamp(message.timestamp())
                .key(message.key())
                .payload(message.payload())
                .headers(message.headers())
                .build();
        return commitLog.append(enriched);
    }

    /**
     * Read messages from a specific partition starting at the given offset.
     */
    public List<TitanMessage> read(TopicPartition tp, long fromOffset, int maxMessages) throws IOException {
        CommitLog commitLog = logs.get(tp);
        if (commitLog == null) {
            return List.of();
        }
        return commitLog.read(fromOffset, maxMessages);
    }

    public int partitionCount(String topic) {
        return (int) logs.keySet().stream()
                .filter(tp -> tp.topic().equals(topic))
                .count();
    }

    private int resolvePartition(TitanMessage message) {
        if (message.partition() >= 0) {
            return message.partition();
        }
        int numPartitions = partitionCount(message.topic());
        if (numPartitions == 0) numPartitions = config.numPartitions();

        if (message.key() != null) {
            return Math.abs(murmurhash3(message.key())) % numPartitions;
        }
        // Simple round-robin via thread-local counter
        return (int) (Thread.currentThread().threadId() % numPartitions);
    }

    /**
     * MurmurHash3 for consistent key-based partitioning.
     */
    private static int murmurhash3(byte[] data) {
        int h = 0x1234ABCD;
        int c1 = 0xcc9e2d51;
        int c2 = 0x1b873593;
        int len = data.length;
        int i = 0;

        while (len >= 4) {
            int k = (data[i] & 0xFF)
                    | ((data[i + 1] & 0xFF) << 8)
                    | ((data[i + 2] & 0xFF) << 16)
                    | ((data[i + 3] & 0xFF) << 24);
            k *= c1;
            k = Integer.rotateLeft(k, 15);
            k *= c2;
            h ^= k;
            h = Integer.rotateLeft(h, 13);
            h = h * 5 + 0xe6546b64;
            i += 4;
            len -= 4;
        }

        int k = 0;
        switch (len) {
            case 3: k ^= (data[i + 2] & 0xFF) << 16;
            case 2: k ^= (data[i + 1] & 0xFF) << 8;
            case 1: k ^= (data[i] & 0xFF);
                k *= c1;
                k = Integer.rotateLeft(k, 15);
                k *= c2;
                h ^= k;
        }

        h ^= data.length;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }

    public void close() throws IOException {
        for (CommitLog commitLog : logs.values()) {
            commitLog.close();
        }
    }
}
