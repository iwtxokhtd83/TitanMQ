package com.titanmq.client;

/**
 * Main entry point for TitanMQ client SDK.
 *
 * <p>Usage:
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
 */
public final class TitanMQ {

    private TitanMQ() {}

    public static <K, V> TitanProducer.Builder<K, V> newProducer() {
        return TitanProducer.builder();
    }

    public static <K, V> TitanConsumer.Builder<K, V> newConsumer() {
        return TitanConsumer.builder();
    }
}
