package com.titanmq.protocol.codec;

import com.titanmq.protocol.Command;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.nio.ByteBuffer;

/**
 * Netty encoder for TitanMQ wire protocol commands.
 */
public class TitanMessageEncoder extends MessageToByteEncoder<Command> {

    @Override
    protected void encode(ChannelHandlerContext ctx, Command cmd, ByteBuf out) {
        ByteBuffer buffer = cmd.encode();
        out.writeBytes(buffer);
    }
}
