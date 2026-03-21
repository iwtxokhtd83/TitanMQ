package com.titanmq.server;

import com.titanmq.common.TitanMessage;
import com.titanmq.common.TopicPartition;
import com.titanmq.core.BackPressureController;
import com.titanmq.core.ConsumerGroupManager;
import com.titanmq.core.TopicManager;
import com.titanmq.protocol.Command;
import com.titanmq.protocol.CommandType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Handles incoming broker requests (produce, fetch, admin commands).
 */
public class BrokerRequestHandler extends SimpleChannelInboundHandler<Command> {

    private static final Logger log = LoggerFactory.getLogger(BrokerRequestHandler.class);

    private final TopicManager topicManager;
    private final ConsumerGroupManager consumerGroupManager;
    private final BackPressureController backPressureController;

    public BrokerRequestHandler(TopicManager topicManager,
                                 ConsumerGroupManager consumerGroupManager,
                                 BackPressureController backPressureController) {
        this.topicManager = topicManager;
        this.consumerGroupManager = consumerGroupManager;
        this.backPressureController = backPressureController;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Command cmd) {
        try {
            switch (cmd.type()) {
                case PRODUCE -> handleProduce(ctx, cmd);
                case FETCH -> handleFetch(ctx, cmd);
                case COMMIT_OFFSET -> handleCommitOffset(ctx, cmd);
                case CREATE_TOPIC -> handleCreateTopic(ctx, cmd);
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

    private void handleProduce(ChannelHandlerContext ctx, Command cmd) throws Exception {
        // Back-pressure check
        if (!backPressureController.tryAcquire()) {
            sendError(ctx, cmd.correlationId(), "Back-pressure: broker is overloaded");
            return;
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(cmd.payload());
            TitanMessage message = TitanMessage.deserialize(buffer);
            long offset = topicManager.append(message);

            // Send ACK with partition and offset
            ByteBuffer ackPayload = ByteBuffer.allocate(12);
            ackPayload.putInt(message.partition());
            ackPayload.putLong(offset);
            ackPayload.flip();
            byte[] ackBytes = new byte[ackPayload.remaining()];
            ackPayload.get(ackBytes);

            ctx.writeAndFlush(new Command(CommandType.PRODUCE_ACK, cmd.correlationId(), ackBytes));
        } finally {
            backPressureController.release();
        }
    }

    private void handleFetch(ChannelHandlerContext ctx, Command cmd) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(cmd.payload());
        int topicLen = buffer.getInt();
        byte[] topicBytes = new byte[topicLen];
        buffer.get(topicBytes);
        String topic = new String(topicBytes);
        int partition = buffer.getInt();
        long fromOffset = buffer.getLong();
        int maxMessages = buffer.getInt();

        TopicPartition tp = new TopicPartition(topic, partition);
        List<TitanMessage> messages = topicManager.read(tp, fromOffset, maxMessages);

        // Serialize response
        ByteBuffer response = ByteBuffer.allocate(4 + messages.stream()
                .mapToInt(m -> m.serialize().remaining()).sum());
        response.putInt(messages.size());
        for (TitanMessage msg : messages) {
            ByteBuffer serialized = msg.serialize();
            response.put(serialized);
        }
        response.flip();
        byte[] responseBytes = new byte[response.remaining()];
        response.get(responseBytes);

        ctx.writeAndFlush(new Command(CommandType.FETCH_RESPONSE, cmd.correlationId(), responseBytes));
    }

    private void handleCommitOffset(ChannelHandlerContext ctx, Command cmd) {
        ByteBuffer buffer = ByteBuffer.wrap(cmd.payload());
        int groupLen = buffer.getInt();
        byte[] groupBytes = new byte[groupLen];
        buffer.get(groupBytes);
        String groupId = new String(groupBytes);
        int topicLen = buffer.getInt();
        byte[] topicBytes = new byte[topicLen];
        buffer.get(topicBytes);
        String topic = new String(topicBytes);
        int partition = buffer.getInt();
        long offset = buffer.getLong();

        consumerGroupManager.commitOffset(groupId, new TopicPartition(topic, partition), offset);
        ctx.writeAndFlush(new Command(CommandType.COMMIT_OFFSET_ACK, cmd.correlationId(), null));
    }

    private void handleCreateTopic(ChannelHandlerContext ctx, Command cmd) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(cmd.payload());
        int topicLen = buffer.getInt();
        byte[] topicBytes = new byte[topicLen];
        buffer.get(topicBytes);
        String topic = new String(topicBytes);
        int numPartitions = buffer.getInt();

        topicManager.createTopic(topic, numPartitions);
        ctx.writeAndFlush(new Command(CommandType.ADMIN_RESPONSE, cmd.correlationId(), new byte[]{1}));
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
