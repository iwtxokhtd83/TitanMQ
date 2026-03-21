package com.titanmq.routing;

import com.titanmq.common.TitanMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Direct exchange: routes messages to destinations with an exact routing key match.
 */
public class DirectExchange implements Exchange {

    private final String name;
    // routingKey -> list of destination topics
    private final ConcurrentHashMap<String, List<String>> bindings = new ConcurrentHashMap<>();

    public DirectExchange(String name) {
        this.name = name;
    }

    @Override
    public String name() { return name; }

    @Override
    public ExchangeType type() { return ExchangeType.DIRECT; }

    @Override
    public void bind(String destinationTopic, String routingKey) {
        bindings.computeIfAbsent(routingKey, k -> new ArrayList<>()).add(destinationTopic);
    }

    @Override
    public void unbind(String destinationTopic, String routingKey) {
        List<String> destinations = bindings.get(routingKey);
        if (destinations != null) {
            destinations.remove(destinationTopic);
        }
    }

    @Override
    public List<String> route(TitanMessage message, String routingKey) {
        return bindings.getOrDefault(routingKey, List.of());
    }
}
