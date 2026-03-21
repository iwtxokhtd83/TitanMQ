package com.titanmq.core.embedded;

import com.titanmq.common.TitanMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * In-process producer that writes directly to the embedded broker.
 * No network serialization, no TCP overhead — just direct method calls.
 */
public class EmbeddedProducer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedProducer.class);

    private final EmbeddedBroker broker;
    private volatile boolean closed = false;

    EmbeddedProducer(EmbeddedBroker broker) {
        this.broker = broker;
    }

    /**
     * Send a message synchronously. Returns the assigned offset.
     */
    public long send(String topic, String key, byte[] payload) throws IOException {
        return send(topic, key, payload, Map.of());
    }

    /**
     * Send a message with headers synchronously.
     */
    public long send(String topic, String key, byte[] payload, Map<String, String> headers) throws IOException {
        checkOpen();
        TitanMessage message = TitanMessage.builder()
                .topic(topic)
                .key(key)
                .payload(payload)
                .headers(headers)
                .build();
        return broker.publish(message);
    }

    /**
     * Send a message asynchronously.
     */
    public CompletableFuture<Long> sendAsync(String topic, String key, byte[] payload) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return send(topic, key, payload);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("Producer is closed");
    }

    @Override
    public void close() {
        closed = true;
        log.debug("Embedded producer closed");
    }
}
