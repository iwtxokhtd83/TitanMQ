package com.titanmq.routing;

import com.titanmq.common.TitanMessage;

import java.util.List;

/**
 * Exchange interface for flexible message routing.
 * Inspired by RabbitMQ's exchange model but integrated with Kafka-style topics.
 */
public interface Exchange {

    /**
     * The name of this exchange.
     */
    String name();

    /**
     * The type of this exchange.
     */
    ExchangeType type();

    /**
     * Bind a destination topic to this exchange with a routing key/pattern.
     */
    void bind(String destinationTopic, String routingKey);

    /**
     * Unbind a destination topic.
     */
    void unbind(String destinationTopic, String routingKey);

    /**
     * Route a message and return the list of destination topics.
     */
    List<String> route(TitanMessage message, String routingKey);
}
