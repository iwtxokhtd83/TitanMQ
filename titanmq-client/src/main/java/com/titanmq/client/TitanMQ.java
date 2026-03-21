package com.titanmq.client;

/**
 * Main entry point for TitanMQ client SDK.
 *
 * <p>Supports two modes of operation:
 *
 * <h3>Networked Mode (default)</h3>
 * <pre>
 * var producer = TitanMQ.newProducer()
 *     .brokers("localhost:9500")
 *     .valueSerializer(new StringSerializer())
 *     .build();
 *
 * var consumer = TitanMQ.newConsumer()
 *     .brokers("localhost:9500")
 *     .group("my-group")
 *     .topics("my-topic")
 *     .valueDeserializer(new StringDeserializer())
 *     .build();
 * </pre>
 *
 * <h3>Embedded/Brokerless Mode (ZeroMQ-style)</h3>
 * <pre>
 * // Use com.titanmq.core.embedded.EmbeddedBroker directly:
 * var broker = EmbeddedBroker.builder()
 *     .dataDir(Path.of("/tmp/titanmq"))
 *     .numPartitions(4)
 *     .build()
 *     .start();
 *
 * var producer = broker.createProducer();
 * var consumer = broker.createConsumer("my-group", "my-topic");
 * </pre>
 */
public final class TitanMQ {

    private TitanMQ() {}

    /**
     * Create a new networked producer builder.
     */
    public static <K, V> TitanProducer.Builder<K, V> newProducer() {
        return TitanProducer.builder();
    }

    /**
     * Create a new networked consumer builder.
     */
    public static <K, V> TitanConsumer.Builder<K, V> newConsumer() {
        return TitanConsumer.builder();
    }
}
