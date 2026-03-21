package com.titanmq.protocol.codec;

import com.titanmq.protocol.Command;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

import java.nio.ByteBuffer;

/**
 * Netty decoder for TitanMQ wire protocol.
 * Uses length-field framing to handle TCP fragmentation.
 */
public class TitanMessageDecoder extends LengthFieldBasedFrameDecoder {

    private static final int MAX_FRAME_LENGTH = 10 * 1024 * 1024; // 10MB

    public TitanMessageDecoder() {
        super(MAX_FRAME_LENGTH, 0, 4, 0, 0);
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        ByteBuf frame = (ByteBuf) super.decode(ctx, in);
        if (frame == null) {
            return null;
        }
        try {
            ByteBuffer nioBuffer = frame.nioBuffer();
            return Command.decode(nioBuffer);
        } finally {
            frame.release();
        }
    }
}
