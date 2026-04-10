package com.titanmq.server;

import com.titanmq.common.TitanMessage;
import com.titanmq.common.TopicPartition;
import com.titanmq.core.BackPressureController;
import com.titanmq.core.ConsumerGroupManager;
import com.titanmq.core.TopicManager;
import com.titanmq.protocol.Command;
import com.titanmq.protocol.CommandType;
import com.titanmq.routing.ExchangeManager;
import com.titanmq.routing.ExchangeType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Handles incoming broker requests including exchange-based routing.
 *
 * <p>When a PRODUCE request arrives, the handler checks if the target topic
 * matches a declared exchange. If so, the message is routed through the exchange
 * to destination topics. Otherwise, it goes directly to the target topic.
 */
public class BrokerRequestHandler extends SimpleChannelInboundHandler<Command> {

    private static final Logger log = LoggerFactory.getLogger(BrokerRequestHandler.class);

    private final TopicManager topicManager;
    private final ConsumerGroupManager consumerGroupManager;
    private final BackPressureController backPressureController;
    private final ExchangeManager exchangeManager;

    public BrokerRequestHandler(TopicManager topicManager,
                                 ConsumerGroupManager consumerGroupManager,
                                 BackPressureController backPressureController,
                                 ExchangeManager exchangeManager) {
        this.topicManager = topicManager;
        this.consumerGroupManager = consumerGroupManager;
        this.backPressureController = backPressureController;
        this.exchangeManager = exchangeManager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Command cmd) {
        try {
            switch (cmd.type()) {
                case PRODUCE -> handleProduce(ctx, cmd);
                case FETCH -> handleFetch(ctx, cmd);
                case COMMIT_OFFSET -> handleCommitOffset(ctx, cmd);
                case CREATE_TOPIC -> handleCreateTopic(ctx, cmd);
                case DECLARE_EXCHANGE -> handleDeclareExchange(ctx, cmd);
                case BIND_EXCHANGE -> handleBindExchange(ctx, cmd);
                case UNBIND_EXCHANGE -> handleUnbindExchange(ctx, cmd);
                default -> {
                    log.warn("Unknown command type: {}", cmd.type());
                    sendError(ctx, cmd.correlationId(), "Unknown command");
                }
            }
        } catch (Exception e) {
            log.error("Error handling command {}", cmd.type(), e);
            sendError(ctx, cmd.correlationId(), e.getMessage());
        }
    }

    /**
     * Handle PRODUCE with exchange routing support.
     *
     * <p>If the message's topic matches a declared exchange, route through it.
     * The routing key is taken from the message header "routingKey" (or the topic name
     * if no header is set). Each destination topic gets a copy of the message.
     *
     * <p>If no exchange matches, write directly to the target topic (backward compatible).
     */
    private void handleProduce(ChannelHandlerContext ctx, Command cmd) throws Exception {
        if (!backPressureController.tryAcquire()) {
            sendError(ctx, cmd.correlationId(), "Back-pressure: broker is overloaded");
            return;
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(cmd.payload());
            TitanMessage message = TitanMessage.deserialize(buffer);

            long offset;
            int partition;

            if (exchangeManager.hasExchange(message.topic())) {
                // Route through exchange
                String routingKey = message.headers().getOrDefault("routingKey", message.topic());
                List<String> destinations = exchangeManager.route(message.topic(), message, routingKey);

                if (destinations.isEmpty()) {
                    sendError(ctx, cmd.correlationId(),
                            "No routing match for exchange '%s' with key '%s'"
                                    .formatted(message.topic(), routingKey));
                    return;
                }

                // Write to all destination topics
                long lastOffset = -1;
                int lastPartition = -1;
                for (String destTopic : destinations) {
                    TitanMessage routed = TitanMessage.builder()
                            .id(message.id())
                            .topic(destTopic)
                            .key(message.key())
                            .payload(message.payload())
                            .headers(message.headers())
                            .timestamp(message.timestamp())
                            .build();
                    lastOffset = topicManager.append(routed);
                    lastPartition = routed.partition();
                }
                offset = lastOffset;
                partition = lastPartition;

                log.debug("Routed message through exchange '{}' to {} destinations",
                        message.topic(), destinations.size());
            } else {
                // Direct write (no exchange)
                offset = topicManager.append(message);
                partition = message.partition();
            }

            // Send ACK
            ByteBuffer ackPayload = ByteBuffer.allocate(12);
            ackPayload.putInt(partition);
            ackPayload.putLong(offset);
            ackPayload.flip();
            byte[] ackBytes = new byte[ackPayload.remaining()];
            ackPayload.get(ackBytes);

            ctx.writeAndFlush(new Command(CommandType.PRODUCE_ACK, cmd.correlationId(), ackBytes));
        } finally {
            backPressureController.release();
        }
    }

    /**
     * Handle DECLARE_EXCHANGE command.
     * Payload: [4B nameLen][NB name][1B exchangeType]
     */
    private void handleDeclareExchange(ChannelHandlerContext ctx, Command cmd) {
        ByteBuffer buf = ByteBuffer.wrap(cmd.payload());
        int nameLen = buf.getInt();
        byte[] nameBytes = new byte[nameLen];
        buf.get(nameBytes);
        String name = new String(nameBytes);
        byte typeCode = buf.get();
        ExchangeType type = ExchangeType.values()[typeCode];

        exchangeManager.declareExchange(name, type);
        ctx.writeAndFlush(new Command(CommandType.ADMIN_RESPONSE, cmd.correlationId(), new byte[]{1}));
    }

    /**
     * Handle BIND_EXCHANGE command.
     * Payload: [4B exchangeNameLen][NB exchangeName][4B destTopicLen][NB destTopic][4B routingKeyLen][NB routingKey]
     */
    private void handleBindExchange(ChannelHandlerContext ctx, Command cmd) {
        ByteBuffer buf = ByteBuffer.wrap(cmd.payload());
        String exchangeName = readString(buf);
        String destTopic = readString(buf);
        String routingKey = readString(buf);

        exchangeManager.bind(exchangeName, destTopic, routingKey);
        ctx.writeAndFlush(new Command(CommandType.ADMIN_RESPONSE, cmd.correlationId(), new byte[]{1}));
    }

    /**
     * Handle UNBIND_EXCHANGE command.
     * Payload: same as BIND_EXCHANGE
     */
    private void handleUnbindExchange(ChannelHandlerContext ctx, Command cmd) {
        ByteBuffer buf = ByteBuffer.wrap(cmd.payload());
        String exchangeName = readString(buf);
        String destTopic = readString(buf);
        String routingKey = readString(buf);

        exchangeManager.unbind(exchangeName, destTopic, routingKey);
        ctx.writeAndFlush(new Command(CommandType.ADMIN_RESPONSE, cmd.correlationId(), new byte[]{1}));
    }

    private void handleFetch(ChannelHandlerContext ctx, Command cmd) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(cmd.payload());
        String topic = readString(buffer);
        int partition = buffer.getInt();
        long fromOffset = buffer.getLong();
        int maxMessages = buffer.getInt();

        TopicPartition tp = new TopicPartition(topic, partition);
        List<TitanMessage> messages = topicManager.read(tp, fromOffset, maxMessages);

        ByteBuffer response = ByteBuffer.allocate(4 + messages.stream()
                .mapToInt(m -> m.serialize().remaining()).sum());
        response.putInt(messages.size());
        for (TitanMessage msg : messages) {
            response.put(msg.serialize());
        }
        response.flip();
        byte[] responseBytes = new byte[response.remaining()];
        response.get(responseBytes);

        ctx.writeAndFlush(new Command(CommandType.FETCH_RESPONSE, cmd.correlationId(), responseBytes));
    }

    private void handleCommitOffset(ChannelHandlerContext ctx, Command cmd) {
        ByteBuffer buffer = ByteBuffer.wrap(cmd.payload());
        String groupId = readString(buffer);
        String topic = readString(buffer);
        int partition = buffer.getInt();
        long offset = buffer.getLong();

        consumerGroupManager.commitOffset(groupId, new TopicPartition(topic, partition), offset);
        ctx.writeAndFlush(new Command(CommandType.COMMIT_OFFSET_ACK, cmd.correlationId(), null));
    }

    private void handleCreateTopic(ChannelHandlerContext ctx, Command cmd) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(cmd.payload());
        String topic = readString(buffer);
        int numPartitions = buffer.getInt();

        topicManager.createTopic(topic, numPartitions);
        ctx.writeAndFlush(new Command(CommandType.ADMIN_RESPONSE, cmd.correlationId(), new byte[]{1}));
    }

    private static String readString(ByteBuffer buf) {
        int len = buf.getInt();
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes);
    }

    private void sendError(ChannelHandlerContext ctx, int correlationId, String message) {
        byte[] errorBytes = message.getBytes();
        ctx.writeAndFlush(new Command(CommandType.ERROR, correlationId, errorBytes));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Channel error", cause);
        ctx.close();
    }
}
