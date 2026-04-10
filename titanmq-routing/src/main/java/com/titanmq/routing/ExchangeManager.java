package com.titanmq.routing;

import com.titanmq.common.TitanMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages named exchanges and routes messages through them.
 *
 * <p>When a producer sends a message, the broker checks if the target topic
 * has any exchange bindings. If so, the message is routed through the exchange
 * to one or more destination topics. If not, the message goes directly to the
 * target topic (backward compatible with plain topic-partition mode).
 *
 * <p>Usage:
 * <pre>
 * ExchangeManager mgr = new ExchangeManager();
 * mgr.declareExchange("order-events", ExchangeType.TOPIC);
 * mgr.bind("order-events", "fulfillment-queue", "order.created");
 * mgr.bind("order-events", "analytics-queue", "order.*");
 *
 * List&lt;String&gt; destinations = mgr.route("order-events", message, "order.created");
 * // → ["fulfillment-queue", "analytics-queue"]
 * </pre>
 */
public class ExchangeManager {

    private static final Logger log = LoggerFactory.getLogger(ExchangeManager.class);

    private final ConcurrentHashMap<String, Exchange> exchanges = new ConcurrentHashMap<>();

    /**
     * Declare a named exchange. If it already exists with the same type, this is a no-op.
     *
     * @throws IllegalArgumentException if the exchange exists with a different type
     */
    public void declareExchange(String name, ExchangeType type) {
        Exchange existing = exchanges.get(name);
        if (existing != null) {
            if (existing.type() != type) {
                throw new IllegalArgumentException(
                        "Exchange '%s' already exists with type %s, cannot redeclare as %s"
                                .formatted(name, existing.type(), type));
            }
            return; // Idempotent
        }

        Exchange exchange = switch (type) {
            case DIRECT -> new DirectExchange(name);
            case TOPIC -> new TopicExchange(name);
            case FANOUT -> new FanoutExchange(name);
            case CONTENT_BASED -> new ContentBasedExchange(name);
        };

        exchanges.put(name, exchange);
        log.info("Declared exchange '{}' of type {}", name, type);
    }

    /**
     * Bind a destination topic to an exchange with a routing key.
     */
    public void bind(String exchangeName, String destinationTopic, String routingKey) {
        Exchange exchange = exchanges.get(exchangeName);
        if (exchange == null) {
            throw new IllegalArgumentException("Exchange '%s' not found".formatted(exchangeName));
        }
        exchange.bind(destinationTopic, routingKey);
        log.info("Bound '{}' -> '{}' with key '{}'", exchangeName, destinationTopic, routingKey);
    }

    /**
     * Unbind a destination topic from an exchange.
     */
    public void unbind(String exchangeName, String destinationTopic, String routingKey) {
        Exchange exchange = exchanges.get(exchangeName);
        if (exchange == null) return;
        exchange.unbind(destinationTopic, routingKey);
        log.info("Unbound '{}' -> '{}' with key '{}'", exchangeName, destinationTopic, routingKey);
    }

    /**
     * Delete an exchange.
     */
    public void deleteExchange(String name) {
        exchanges.remove(name);
        log.info("Deleted exchange '{}'", name);
    }

    /**
     * Route a message through an exchange.
     *
     * @return list of destination topics, or empty if exchange not found
     */
    public List<String> route(String exchangeName, TitanMessage message, String routingKey) {
        Exchange exchange = exchanges.get(exchangeName);
        if (exchange == null) return List.of();
        return exchange.route(message, routingKey);
    }

    /**
     * Check if an exchange exists.
     */
    public boolean hasExchange(String name) {
        return exchanges.containsKey(name);
    }

    /**
     * Get exchange info for admin queries.
     */
    public Exchange getExchange(String name) {
        return exchanges.get(name);
    }

    /**
     * List all exchange names.
     */
    public List<String> listExchanges() {
        return new ArrayList<>(exchanges.keySet());
    }
}
