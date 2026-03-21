package com.titanmq.core.embedded;

import com.titanmq.common.TitanMessage;
import com.titanmq.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedBrokerTest {

    private Path tempDir;
    private EmbeddedBroker broker;

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("titanmq-embedded-test");
        broker = EmbeddedBroker.builder()
                .dataDir(tempDir)
                .numPartitions(4)
                .segmentSize(1024 * 1024)
                .build()
                .start();
    }

    @AfterEach
    void teardown() throws IOException {
        broker.close();
        Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete());
    }

    @Test
    void shouldPublishAndConsumeDirectly() throws IOException {
        broker.createTopic("test-topic", 1);

        TitanMessage msg = TitanMessage.builder()
                .topic("test-topic")
                .partition(0)
                .key("key-1")
                .payload("hello embedded")
                .build();

        long offset = broker.publish(msg);
        assertEquals(0, offset);

        List<TitanMessage> messages = broker.consume(
                new TopicPartition("test-topic", 0), 0, 10);
        assertEquals(1, messages.size());
        assertArrayEquals("hello embedded".getBytes(), messages.get(0).payload());
    }

    @Test
    void producerAndConsumerShouldWork() throws IOException {
        broker.createTopic("orders", 2);

        EmbeddedProducer producer = broker.createProducer();
        producer.send("orders", "order-1", "payload-1".getBytes());
        producer.send("orders", "order-2", "payload-2".getBytes());
        producer.send("orders", "order-3", "payload-3".getBytes());

        EmbeddedConsumer consumer = broker.createConsumer("test-group", "orders");
        List<TitanMessage> messages = consumer.poll(100);

        assertEquals(3, messages.size());

        producer.close();
        consumer.close();
    }

    @Test
    void subscribeShouldReceiveMessages() throws Exception {
        broker.createTopic("events", 1);

        List<TitanMessage> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(5);

        EmbeddedConsumer consumer = broker.createConsumer("sub-group", "events");
        consumer.subscribe(msg -> {
            received.add(msg);
            latch.countDown();
        });

        // Give subscriber time to start
        Thread.sleep(50);

        EmbeddedProducer producer = broker.createProducer();
        for (int i = 0; i < 5; i++) {
            producer.send("events", "key-" + i, ("event-" + i).getBytes());
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive all 5 messages");
        assertEquals(5, received.size());

        producer.close();
        consumer.close();
    }

    @Test
    void consumerShouldTrackOffsets() throws IOException {
        broker.createTopic("offset-test", 1);

        EmbeddedProducer producer = broker.createProducer();
        for (int i = 0; i < 10; i++) {
            producer.send("offset-test", null, ("msg-" + i).getBytes());
        }

        EmbeddedConsumer consumer = broker.createConsumer("group-1", "offset-test");

        // First poll: get first batch
        List<TitanMessage> batch1 = consumer.poll(5);
        assertEquals(5, batch1.size());

        // Second poll: should continue from where we left off
        List<TitanMessage> batch2 = consumer.poll(5);
        assertEquals(5, batch2.size());

        // Third poll: no more messages
        List<TitanMessage> batch3 = consumer.poll(5);
        assertEquals(0, batch3.size());

        producer.close();
        consumer.close();
    }

    @Test
    void seekShouldResetOffset() throws IOException {
        broker.createTopic("seek-test", 1);

        EmbeddedProducer producer = broker.createProducer();
        for (int i = 0; i < 10; i++) {
            producer.send("seek-test", null, ("msg-" + i).getBytes());
        }

        EmbeddedConsumer consumer = broker.createConsumer("group-1", "seek-test");
        consumer.poll(10); // Read all

        // Seek back to beginning
        consumer.seek(new TopicPartition("seek-test", 0), 0);
        List<TitanMessage> replayed = consumer.poll(10);
        assertEquals(10, replayed.size());

        producer.close();
        consumer.close();
    }

    @Test
    void multipleProducersShouldWorkConcurrently() throws Exception {
        broker.createTopic("concurrent", 4);

        int producerCount = 4;
        int messagesPerProducer = 100;
        CountDownLatch latch = new CountDownLatch(producerCount);

        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    EmbeddedProducer producer = broker.createProducer();
                    for (int i = 0; i < messagesPerProducer; i++) {
                        producer.send("concurrent", "key-" + producerId,
                                ("msg-" + producerId + "-" + i).getBytes());
                    }
                    producer.close();
                } catch (IOException e) {
                    fail("Producer failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));

        // Verify all messages were written
        EmbeddedConsumer consumer = broker.createConsumer("verify-group", "concurrent");
        List<TitanMessage> all = consumer.poll(producerCount * messagesPerProducer);
        assertEquals(producerCount * messagesPerProducer, all.size());

        consumer.close();
    }

    @Test
    void asyncSendShouldWork() throws Exception {
        broker.createTopic("async-test", 1);

        EmbeddedProducer producer = broker.createProducer();
        Long offset = producer.sendAsync("async-test", "key", "async-payload".getBytes())
                .get(5, TimeUnit.SECONDS);

        assertNotNull(offset);
        assertTrue(offset >= 0);

        producer.close();
    }

    @Test
    void shouldRejectAfterClose() throws IOException {
        broker.createTopic("close-test", 1);
        EmbeddedProducer producer = broker.createProducer();
        producer.close();

        assertThrows(IllegalStateException.class, () ->
                producer.send("close-test", "key", "data".getBytes()));
    }
}
