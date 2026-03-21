package com.titanmq.routing;

import com.titanmq.common.TitanMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fanout exchange: routes messages to ALL bound destinations regardless of routing key.
 */
public class FanoutExchange implements Exchange {

    private final String name;
    private final CopyOnWriteArrayList<String> destinations = new CopyOnWriteArrayList<>();

    public FanoutExchange(String name) {
        this.name = name;
    }

    @Override
    public String name() { return name; }

    @Override
    public ExchangeType type() { return ExchangeType.FANOUT; }

    @Override
    public void bind(String destinationTopic, String routingKey) {
        if (!destinations.contains(destinationTopic)) {
            destinations.add(destinationTopic);
        }
    }

    @Override
    public void unbind(String destinationTopic, String routingKey) {
        destinations.remove(destinationTopic);
    }

    @Override
    public List<String> route(TitanMessage message, String routingKey) {
        return new ArrayList<>(destinations);
    }
}
