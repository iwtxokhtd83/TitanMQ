package com.titanmq.cluster;

import com.titanmq.cluster.RaftRpcMessages.*;

import java.util.concurrent.CompletableFuture;

/**
 * Transport abstraction for Raft RPC communication between nodes.
 * Implementations can use TCP (Netty), in-process queues (for testing), etc.
 */
public interface RaftTransport {

    /**
     * Send a VoteRequest to a peer and return the response asynchronously.
     */
    CompletableFuture<VoteResponse> sendVoteRequest(String peerId, VoteRequest request);

    /**
     * Send an AppendEntries RPC to a peer and return the response asynchronously.
     */
    CompletableFuture<AppendEntriesResponse> sendAppendEntries(String peerId, AppendEntriesRequest request);

    /**
     * Start listening for incoming RPCs.
     */
    void start();

    /**
     * Stop the transport.
     */
    void stop();
}
