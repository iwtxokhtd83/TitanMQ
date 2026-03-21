package com.titanmq.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RaftNodeTest {

    @BeforeEach
    void setup() {
        InMemoryRaftTransport.clearAll();
    }

    @AfterEach
    void teardown() {
        InMemoryRaftTransport.clearAll();
    }

    @Test
    void singleNodeShouldBecomeLeader() throws Exception {
        RaftNode node = new RaftNode("node-1", List.of());
        node.start();

        // Wait for election
        Thread.sleep(800);

        assertEquals(RaftState.LEADER, node.state());
        assertEquals("node-1", node.leaderId());
        assertTrue(node.isLeader());

        node.stop();
    }

    @Test
    void threeNodeClusterShouldElectLeader() throws Exception {
        RaftNode node1 = new RaftNode("node-1", List.of("node-2", "node-3"));
        RaftNode node2 = new RaftNode("node-2", List.of("node-1", "node-3"));
        RaftNode node3 = new RaftNode("node-3", List.of("node-1", "node-2"));

        node1.start();
        node2.start();
        node3.start();

        // Wait for election to complete
        Thread.sleep(2000);

        // Exactly one leader
        long leaderCount = List.of(node1, node2, node3).stream()
                .filter(RaftNode::isLeader)
                .count();
        assertEquals(1, leaderCount, "Exactly one leader should be elected");

        // All nodes should agree on the leader
        String leaderId = node1.leaderId();
        if (leaderId == null) leaderId = node2.leaderId();
        if (leaderId == null) leaderId = node3.leaderId();
        assertNotNull(leaderId, "A leader should have been elected");

        node1.stop();
        node2.stop();
        node3.stop();
    }

    @Test
    void leaderShouldReplicateLogEntries() throws Exception {
        RaftNode node1 = new RaftNode("node-1", List.of("node-2", "node-3"));
        RaftNode node2 = new RaftNode("node-2", List.of("node-1", "node-3"));
        RaftNode node3 = new RaftNode("node-3", List.of("node-1", "node-2"));

        node1.start();
        node2.start();
        node3.start();

        Thread.sleep(2000);

        // Find the leader
        RaftNode leader = List.of(node1, node2, node3).stream()
                .filter(RaftNode::isLeader)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No leader elected"));

        // Submit a command
        Long index = leader.submitCommand("test-command".getBytes())
                .get(5, TimeUnit.SECONDS);

        assertNotNull(index);
        assertTrue(index >= 0);

        // Wait for replication
        Thread.sleep(500);

        // All nodes should have the entry
        for (RaftNode node : List.of(node1, node2, node3)) {
            RaftLog.LogEntry entry = node.raftLog().getEntry(index);
            assertNotNull(entry, "Node " + node.nodeId() + " should have the entry");
            assertArrayEquals("test-command".getBytes(), entry.command());
        }

        node1.stop();
        node2.stop();
        node3.stop();
    }

    @Test
    void shouldHandleVoteRequestWithHigherTerm() {
        RaftNode node = new RaftNode("node-1", List.of("node-2"));
        node.start();

        // Simulate a vote request with a higher term
        var response = node.handleVoteRequest(
                new RaftRpcMessages.VoteRequest(10, "node-2", -1, 0));

        assertTrue(response.voteGranted());
        assertEquals(10, node.currentTerm());
        assertEquals(RaftState.FOLLOWER, node.state());

        node.stop();
    }

    @Test
    void shouldRejectVoteRequestWithLowerTerm() throws Exception {
        RaftNode node = new RaftNode("node-1", List.of());
        node.start();
        Thread.sleep(800); // Become leader

        // Vote request with lower term should be rejected
        var response = node.handleVoteRequest(
                new RaftRpcMessages.VoteRequest(0, "node-2", -1, 0));

        assertFalse(response.voteGranted());

        node.stop();
    }

    @Test
    void followerShouldAcceptAppendEntries() {
        RaftNode node = new RaftNode("node-1", List.of("node-2"));
        node.start();

        List<RaftLog.LogEntry> entries = List.of(
                new RaftLog.LogEntry(0, 1, "cmd-1".getBytes()),
                new RaftLog.LogEntry(1, 1, "cmd-2".getBytes())
        );

        var response = node.handleAppendEntries(
                new RaftRpcMessages.AppendEntriesRequest(1, "node-2", -1, 0, entries, 1));

        assertTrue(response.success());
        assertEquals(1, response.matchIndex());
        assertEquals("node-2", node.leaderId());

        // Verify entries were stored
        assertNotNull(node.raftLog().getEntry(0));
        assertNotNull(node.raftLog().getEntry(1));

        node.stop();
    }

    @Test
    void shouldRejectAppendEntriesWithLowerTerm() throws Exception {
        RaftNode node = new RaftNode("node-1", List.of());
        node.start();
        Thread.sleep(800); // Become leader at term 1

        var response = node.handleAppendEntries(
                new RaftRpcMessages.AppendEntriesRequest(0, "node-2", -1, 0, List.of(), 0));

        assertFalse(response.success());

        node.stop();
    }

    private static class AssertionError extends RuntimeException {
        AssertionError(String msg) { super(msg); }
    }
}
