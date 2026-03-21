package com.titanmq.server;

import com.titanmq.common.config.BrokerConfig;
import com.titanmq.core.BackPressureController;
import com.titanmq.core.ConsumerGroupManager;
import com.titanmq.core.TopicManager;
import com.titanmq.protocol.codec.TitanMessageDecoder;
import com.titanmq.protocol.codec.TitanMessageEncoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty-based network server for the TitanMQ broker.
 *
 * <p>Uses a boss/worker thread model:
 * <ul>
 *   <li>Boss group: accepts incoming connections</li>
 *   <li>Worker group: handles I/O for established connections</li>
 * </ul>
 */
public class BrokerNetworkServer {

    private static final Logger log = LoggerFactory.getLogger(BrokerNetworkServer.class);

    private final BrokerConfig config;
    private final TopicManager topicManager;
    private final ConsumerGroupManager consumerGroupManager;
    private final BackPressureController backPressureController;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public BrokerNetworkServer(BrokerConfig config, TopicManager topicManager,
                                ConsumerGroupManager consumerGroupManager,
                                BackPressureController backPressureController) {
        this.config = config;
        this.topicManager = topicManager;
        this.consumerGroupManager = consumerGroupManager;
        this.backPressureController = backPressureController;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(config.numNetworkThreads());

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("decoder", new TitanMessageDecoder())
                                .addLast("encoder", new TitanMessageEncoder())
                                .addLast("handler", new BrokerRequestHandler(
                                        topicManager, consumerGroupManager, backPressureController));
                    }
                });

        ChannelFuture future = bootstrap.bind(config.port()).sync();
        serverChannel = future.channel();
        log.info("TitanMQ network server listening on port {}", config.port());
    }

    public void stop() {
        if (serverChannel != null) serverChannel.close();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
    }
}
