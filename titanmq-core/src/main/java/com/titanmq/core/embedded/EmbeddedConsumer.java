package com.titanmq.core.embedded;

import com.titanmq.common.TitanMessage;
import com.titanmq.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * In-process consumer that reads directly from the embedded broker.
 * Supports both pull (poll) and push (subscribe) consumption patterns.
 */
public class EmbeddedConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedConsumer.class);

    private final EmbeddedBroker broker;
    private final String groupId;
    private final List<String> topics;
    private final ConcurrentHashMap<TopicPartition, Long> offsets = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    EmbeddedConsumer(EmbeddedBroker broker, String groupId, List<String> topics) {
        this.broker = broker;
        this.groupId = groupId;
        this.topics = topics;
    }

    /**
     * Poll for messages across all assigned partitions.
     */
    public List<TitanMessage> poll(int maxMessages) throws IOException {
        List<TitanMessage> result = new ArrayList<>();
        for (String topic : topics) {
            int partitions = broker.partitionCount(topic);
            if (partitions == 0) continue;

            int perPartition = Math.max(1, maxMessages / partitions);
            for (int p = 0; p < partitions; p++) {
                TopicPartition tp = new TopicPartition(topic, p);
                long offset = offsets.getOrDefault(tp, 0L);
                List<TitanMessage> msgs = broker.consume(tp, offset, perPartition);
                result.addAll(msgs);
                if (!msgs.isEmpty()) {
                    offsets.put(tp, offset + msgs.size());
                }
            }
        }
        return result;
    }

    /**
     * Subscribe with a push-based handler. Runs in a background thread.
     */
    public void subscribe(Consumer<TitanMessage> handler) {
        running.set(true);
        executor.submit(() -> {
            log.info("Embedded consumer [group={}] started on topics {}", groupId, topics);
            while (running.get()) {
                try {
                    List<TitanMessage> messages = poll(100);
                    for (TitanMessage msg : messages) {
                        handler.accept(msg);
                    }
                    if (messages.isEmpty()) {
                        Thread.sleep(10); // Avoid busy-wait
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (running.get()) {
                        log.error("Error in embedded consumer", e);
                    }
                }
            }
        });
    }

    /**
     * Seek to a specific offset for a partition.
     */
    public void seek(TopicPartition tp, long offset) {
        offsets.put(tp, offset);
    }

    /**
     * Reset all offsets to the beginning.
     */
    public void seekToBeginning() {
        offsets.clear();
    }

    /**
     * Get the current offset for a partition.
     */
    public long currentOffset(TopicPartition tp) {
        return offsets.getOrDefault(tp, 0L);
    }

    @Override
    public void close() {
        running.set(false);
        executor.shutdown();
        log.debug("Embedded consumer [group={}] closed", groupId);
    }
}
