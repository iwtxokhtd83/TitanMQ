package com.titanmq.cluster;

import com.titanmq.cluster.RaftRpcMessages.*;
import com.titanmq.protocol.Command;
import com.titanmq.protocol.CommandType;
import com.titanmq.protocol.codec.TitanMessageDecoder;
import com.titanmq.protocol.codec.TitanMessageEncoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Netty-based Raft transport for real network communication between broker nodes.
 * Each node runs a server to receive RPCs and creates client connections to peers.
 */
public class NettyRaftTransport implements RaftTransport {

    private static final Logger log = LoggerFactory.getLogger(NettyRaftTransport.class);

    private final String localNodeId;
    private final int listenPort;
    private final Map<String, String> peerAddresses; // peerId -> "host:port"
    private final RaftNode raftNode;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    // Client connections to peers
    private final ConcurrentHashMap<String, Channel> peerChannels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CompletableFuture<?>> pendingRpcs = new ConcurrentHashMap<>();
    private final AtomicInteger correlationIdGen = new AtomicInteger(0);

    public NettyRaftTransport(String localNodeId, int listenPort,
                               Map<String, String> peerAddresses, RaftNode raftNode) {
        this.localNodeId = localNodeId;
        this.listenPort = listenPort;
        this.peerAddresses = peerAddresses;
        this.raftNode = raftNode;
    }

    @Override
    public void start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(2);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new TitanMessageDecoder())
                                    .addLast(new TitanMessageEncoder())
                                    .addLast(new RaftServerHandler());
                        }
                    });

            serverChannel = bootstrap.bind(listenPort).sync().channel();
            log.info("Raft transport listening on port {} for node {}", listenPort, localNodeId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to start Raft transport", e);
        }
    }

    @Override
    public void stop() {
        peerChannels.values().forEach(Channel::close);
        if (serverChannel != null) serverChannel.close();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<VoteResponse> sendVoteRequest(String peerId, VoteRequest request) {
        int correlationId = correlationIdGen.incrementAndGet();
        CompletableFuture<VoteResponse> future = new CompletableFuture<>();
        pendingRpcs.put(correlationId, future);

        Channel channel = getOrConnectPeer(peerId);
        if (channel == null || !channel.isActive()) {
            future.completeExceptionally(new RuntimeException("Cannot connect to peer " + peerId));
            pendingRpcs.remove(correlationId);
            return future;
        }

        Command cmd = new Command(CommandType.VOTE_REQUEST, correlationId, request.serialize());
        channel.writeAndFlush(cmd);
        return future;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<AppendEntriesResponse> sendAppendEntries(String peerId, AppendEntriesRequest request) {
        int correlationId = correlationIdGen.incrementAndGet();
        CompletableFuture<AppendEntriesResponse> future = new CompletableFuture<>();
        pendingRpcs.put(correlationId, future);

        Channel channel = getOrConnectPeer(peerId);
        if (channel == null || !channel.isActive()) {
            future.completeExceptionally(new RuntimeException("Cannot connect to peer " + peerId));
            pendingRpcs.remove(correlationId);
            return future;
        }

        Command cmd = new Command(CommandType.APPEND_ENTRIES, correlationId, request.serialize());
        channel.writeAndFlush(cmd);
        return future;
    }

    private Channel getOrConnectPeer(String peerId) {
        Channel existing = peerChannels.get(peerId);
        if (existing != null && existing.isActive()) return existing;

        String address = peerAddresses.get(peerId);
        if (address == null) return null;

        String[] parts = address.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        try {
            Bootstrap bootstrap = new Bootstrap()
                    .group(workerGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new TitanMessageDecoder())
                                    .addLast(new TitanMessageEncoder())
                                    .addLast(new RaftClientHandler());
                        }
                    });

            Channel channel = bootstrap.connect(host, port).sync().channel();
            peerChannels.put(peerId, channel);
            return channel;
        } catch (Exception e) {
            log.debug("Failed to connect to peer {} at {}: {}", peerId, address, e.getMessage());
            return null;
        }
    }

    /**
     * Handles incoming Raft RPCs on the server side.
     */
    private class RaftServerHandler extends SimpleChannelInboundHandler<Command> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Command cmd) {
            switch (cmd.type()) {
                case VOTE_REQUEST -> {
                    VoteRequest request = VoteRequest.deserialize(cmd.payload());
                    VoteResponse response = raftNode.handleVoteRequest(request);
                    ctx.writeAndFlush(new Command(CommandType.VOTE_RESPONSE, cmd.correlationId(),
                            response.serialize()));
                }
                case APPEND_ENTRIES -> {
                    AppendEntriesRequest request = AppendEntriesRequest.deserialize(cmd.payload());
                    AppendEntriesResponse response = raftNode.handleAppendEntries(request);
                    ctx.writeAndFlush(new Command(CommandType.APPEND_ENTRIES_RESPONSE, cmd.correlationId(),
                            response.serialize()));
                }
                default -> log.warn("Unexpected command type in Raft transport: {}", cmd.type());
            }
        }
    }

    /**
     * Handles responses to outgoing Raft RPCs on the client side.
     */
    @SuppressWarnings("unchecked")
    private class RaftClientHandler extends SimpleChannelInboundHandler<Command> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Command cmd) {
            CompletableFuture<?> future = pendingRpcs.remove(cmd.correlationId());
            if (future == null) return;

            switch (cmd.type()) {
                case VOTE_RESPONSE -> {
                    VoteResponse response = VoteResponse.deserialize(cmd.payload());
                    ((CompletableFuture<VoteResponse>) future).complete(response);
                }
                case APPEND_ENTRIES_RESPONSE -> {
                    AppendEntriesResponse response = AppendEntriesResponse.deserialize(cmd.payload());
                    ((CompletableFuture<AppendEntriesResponse>) future).complete(response);
                }
                default -> log.warn("Unexpected response type: {}", cmd.type());
            }
        }
    }
}
