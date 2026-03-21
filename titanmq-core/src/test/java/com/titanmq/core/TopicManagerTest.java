package com.titanmq.core;

import com.titanmq.common.TitanMessage;
import com.titanmq.common.TopicPartition;
import com.titanmq.common.config.BrokerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TopicManagerTest {

    private Path tempDir;
    private TopicManager topicManager;

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("titanmq-topic-test");
        BrokerConfig config = new BrokerConfig()
                .dataDir(tempDir)
                .numPartitions(4)
                .segmentSizeBytes(1024 * 1024);
        topicManager = new TopicManager(config);
    }

    @AfterEach
    void teardown() throws IOException {
        topicManager.close();
        Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete());
    }

    @Test
    void shouldCreateTopicWithPartitions() throws IOException {
        topicManager.createTopic("orders", 4);
        assertEquals(4, topicManager.partitionCount("orders"));
    }

    @Test
    void shouldAutoCreateTopicOnAppend() throws IOException {
        TitanMessage msg = TitanMessage.builder()
                .topic("auto-topic")
                .payload("data")
                .build();

        topicManager.append(msg);
        assertTrue(topicManager.partitionCount("auto-topic") > 0);
    }

    @Test
    void shouldRouteByKeyToSamePartition() throws IOException {
        topicManager.createTopic("keyed-topic", 8);

        // Same key should always go to same partition
        for (int i = 0; i < 10; i++) {
            topicManager.append(TitanMessage.builder()
                    .topic("keyed-topic")
                    .key("consistent-key")
                    .payload("msg-" + i)
                    .build());
        }

        // Find which partition has messages
        int partitionWithMessages = -1;
        for (int p = 0; p < 8; p++) {
            List<TitanMessage> msgs = topicManager.read(new TopicPartition("keyed-topic", p), 0, 100);
            if (!msgs.isEmpty()) {
                if (partitionWithMessages == -1) {
                    partitionWithMessages = p;
                    assertEquals(10, msgs.size(), "All messages with same key should be in same partition");
                } else {
                    fail("Messages with same key ended up in multiple partitions");
                }
            }
        }
        assertTrue(partitionWithMessages >= 0, "Should have found messages in at least one partition");
    }

    @Test
    void shouldReadFromSpecificPartition() throws IOException {
        topicManager.createTopic("read-test", 2);

        topicManager.append(TitanMessage.builder()
                .topic("read-test")
                .partition(0)
                .payload("partition-0-msg")
                .build());

        topicManager.append(TitanMessage.builder()
                .topic("read-test")
                .partition(1)
                .payload("partition-1-msg")
                .build());

        List<TitanMessage> p0 = topicManager.read(new TopicPartition("read-test", 0), 0, 10);
        List<TitanMessage> p1 = topicManager.read(new TopicPartition("read-test", 1), 0, 10);

        assertEquals(1, p0.size());
        assertEquals(1, p1.size());
        assertArrayEquals("partition-0-msg".getBytes(), p0.get(0).payload());
        assertArrayEquals("partition-1-msg".getBytes(), p1.get(0).payload());
    }
}
