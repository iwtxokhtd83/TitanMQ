package com.titanmq.core.embedded;

import com.titanmq.common.TitanMessage;
import com.titanmq.common.TopicPartition;
import com.titanmq.common.config.BrokerConfig;
import com.titanmq.core.BackPressureController;
import com.titanmq.core.TopicManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Embedded (brokerless) mode for TitanMQ.
 *
 * <p>Runs the entire message broker in-process without any network I/O.
 * Inspired by ZeroMQ's library-based approach, this mode provides:
 * <ul>
 *   <li>Ultra-low latency (no network serialization/deserialization)</li>
 *   <li>Zero deployment overhead (no separate broker process)</li>
 *   <li>Perfect for testing, single-process applications, and inter-thread communication</li>
 *   <li>Same API as the networked client — easy to switch between modes</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * EmbeddedBroker broker = EmbeddedBroker.builder()
 *     .dataDir(Path.of("/tmp/titanmq"))
 *     .numPartitions(4)
 *     .build();
 *
 * EmbeddedProducer producer = broker.createProducer();
 * producer.send("my-topic", "key", "value".getBytes());
 *
 * EmbeddedConsumer consumer = broker.createConsumer("my-group", "my-topic");
 * consumer.poll(100).forEach(msg -> process(msg));
 * </pre>
 */
public class EmbeddedBroker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedBroker.class);

    private final BrokerConfig config;
    private final TopicManager topicManager;
    private final BackPressureController backPressureController;
    private volatile boolean running = false;

    private EmbeddedBroker(BrokerConfig config) {
        this.config = config;
        this.topicManager = new TopicManager(config);
        this.backPressureController = new BackPressureController(
                config.backPressureHighWaterMark(),
                config.backPressureLowWaterMark()
        );
    }

    /**
     * Start the embedded broker.
     */
    public EmbeddedBroker start() {
        running = true;
        log.info("Embedded TitanMQ broker started (in-process, no network)");
        log.info("  Data directory: {}", config.dataDir());
        log.info("  Partitions: {}", config.numPartitions());
        return this;
    }

    /**
     * Append a message directly (no network overhead).
     */
    public long publish(TitanMessage message) throws IOException {
        checkRunning();
        if (!backPressureController.tryAcquire()) {
            throw new IllegalStateException("Back-pressure: broker is overloaded");
        }
        try {
            return topicManager.append(message);
        } finally {
            backPressureController.release();
        }
    }

    /**
     * Read messages directly from a partition (no network overhead).
     */
    public List<TitanMessage> consume(TopicPartition tp, long fromOffset, int maxMessages) throws IOException {
        checkRunning();
        return topicManager.read(tp, fromOffset, maxMessages);
    }

    /**
     * Create a topic with the specified number of partitions.
     */
    public void createTopic(String topic, int numPartitions) throws IOException {
        checkRunning();
        topicManager.createTopic(topic, numPartitions);
    }

    /**
     * Create an embedded producer for this broker.
     */
    public EmbeddedProducer createProducer() {
        return new EmbeddedProducer(this);
    }

    /**
     * Create an embedded consumer for this broker.
     */
    public EmbeddedConsumer createConsumer(String groupId, String... topics) {
        return new EmbeddedConsumer(this, groupId, List.of(topics));
    }

    public int partitionCount(String topic) {
        return topicManager.partitionCount(topic);
    }

    public boolean isRunning() { return running; }

    private void checkRunning() {
        if (!running) throw new IllegalStateException("Embedded broker is not running");
    }

    @Override
    public void close() throws IOException {
        running = false;
        topicManager.close();
        log.info("Embedded TitanMQ broker stopped");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final BrokerConfig config = new BrokerConfig();

        public Builder dataDir(Path path) { config.dataDir(path); return this; }
        public Builder numPartitions(int n) { config.numPartitions(n); return this; }
        public Builder segmentSize(long bytes) { config.segmentSizeBytes(bytes); return this; }
        public Builder backPressure(int high, int low) {
            config.backPressureHighWaterMark(high);
            config.backPressureLowWaterMark(low);
            return this;
        }

        public EmbeddedBroker build() {
            return new EmbeddedBroker(config);
        }
    }
}
