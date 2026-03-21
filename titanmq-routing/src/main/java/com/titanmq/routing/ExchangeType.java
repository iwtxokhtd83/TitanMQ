package com.titanmq.routing;

/**
 * Supported exchange types.
 */
public enum ExchangeType {
    /** Route to exact matching routing key */
    DIRECT,
    /** Route using wildcard pattern matching (*.logs, orders.#) */
    TOPIC,
    /** Route to all bound destinations */
    FANOUT,
    /** Route based on message header/content matching */
    CONTENT_BASED
}
