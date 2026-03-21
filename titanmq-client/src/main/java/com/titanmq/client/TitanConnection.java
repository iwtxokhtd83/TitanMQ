package com.titanmq.client;

import com.titanmq.protocol.Command;
import com.titanmq.protocol.codec.TitanMessageDecoder;
import com.titanmq.protocol.codec.TitanMessageEncoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty-based connection to a TitanMQ broker.
 */
public class TitanConnection {

    private static final Logger log = LoggerFactory.getLogger(TitanConnection.class);

    private final String brokers;
    private EventLoopGroup group;
    private Channel channel;

    public TitanConnection(String brokers) {
        this.brokers = brokers;
    }

    public void connect() {
        String[] parts = brokers.split(",")[0].split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9500;

        group = new NioEventLoopGroup(1);
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new TitanMessageDecoder())
                                .addLast(new TitanMessageEncoder())
                                .addLast(new ClientHandler());
                    }
                });

        try {
            ChannelFuture future = bootstrap.connect(host, port).sync();
            channel = future.channel();
            log.info("Connected to broker at {}:{}", host, port);
        } catch (Exception e) {
            log.warn("Could not connect to broker at {}:{} - running in offline mode", host, port);
        }
    }

    public void send(Command command) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(command);
        }
    }

    public void close() {
        if (channel != null) {
            channel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    private static class ClientHandler extends SimpleChannelInboundHandler<Command> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Command cmd) {
            log.debug("Received response: type={}, correlationId={}", cmd.type(), cmd.correlationId());
        }
    }
}
