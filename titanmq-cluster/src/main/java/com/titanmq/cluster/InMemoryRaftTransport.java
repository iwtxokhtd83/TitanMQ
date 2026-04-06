package com.titanmq.cluster;

import com.titanmq.cluster.RaftRpcMessages.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory Raft transport for testing and single-JVM clusters.
 * Routes RPCs directly between RaftNode instances without network I/O.
 */
public class InMemoryRaftTransport implements RaftTransport {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRaftTransport.class);

    // Global registry of all nodes in this JVM
    private static final ConcurrentHashMap<String, RaftNode> NODES = new ConcurrentHashMap<>();

    private final String localNodeId;

    public InMemoryRaftTransport(String localNodeId) {
        this.localNodeId = localNodeId;
    }

    public static void registerNode(String nodeId, RaftNode node) {
        NODES.put(nodeId, node);
    }

    public static void unregisterNode(String nodeId) {
        NODES.remove(nodeId);
    }

    public static void clearAll() {
        NODES.clear();
    }

    @Override
    public CompletableFuture<VoteResponse> sendVoteRequest(String peerId, VoteRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            RaftNode peer = NODES.get(peerId);
            if (peer == null) {
                log.warn("Node {} not found for VoteRequest", peerId);
                return new VoteResponse(request.term(), false, peerId);
            }
            return peer.handleVoteRequest(request);
        });
    }

    @Override
    public CompletableFuture<AppendEntriesResponse> sendAppendEntries(String peerId, AppendEntriesRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            RaftNode peer = NODES.get(peerId);
            if (peer == null) {
                log.warn("Node {} not found for AppendEntries", peerId);
                return new AppendEntriesResponse(request.term(), false, peerId, -1, 0, 0);
            }
            return peer.handleAppendEntries(request);
        });
    }

    @Override
    public void start() {
        log.info("InMemoryRaftTransport started for node {}", localNodeId);
    }

    @Override
    public void stop() {
        NODES.remove(localNodeId);
    }
}
