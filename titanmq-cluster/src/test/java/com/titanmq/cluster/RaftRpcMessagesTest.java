package com.titanmq.cluster;

import com.titanmq.cluster.RaftRpcMessages.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RaftRpcMessagesTest {

    @Test
    void voteRequestShouldRoundTrip() {
        VoteRequest original = new VoteRequest(5, "node-1", 10, 3);
        byte[] serialized = original.serialize();
        VoteRequest deserialized = VoteRequest.deserialize(serialized);

        assertEquals(5, deserialized.term());
        assertEquals("node-1", deserialized.candidateId());
        assertEquals(10, deserialized.lastLogIndex());
        assertEquals(3, deserialized.lastLogTerm());
    }

    @Test
    void voteResponseShouldRoundTrip() {
        VoteResponse original = new VoteResponse(5, true, "node-2");
        byte[] serialized = original.serialize();
        VoteResponse deserialized = VoteResponse.deserialize(serialized);

        assertEquals(5, deserialized.term());
        assertTrue(deserialized.voteGranted());
        assertEquals("node-2", deserialized.voterId());
    }

    @Test
    void appendEntriesRequestShouldRoundTrip() {
        List<RaftLog.LogEntry> entries = List.of(
                new RaftLog.LogEntry(0, 1, "cmd-1".getBytes()),
                new RaftLog.LogEntry(1, 1, "cmd-2".getBytes()),
                new RaftLog.LogEntry(2, 2, "cmd-3".getBytes())
        );

        AppendEntriesRequest original = new AppendEntriesRequest(
                3, "leader-1", 5, 2, entries, 4);
        byte[] serialized = original.serialize();
        AppendEntriesRequest deserialized = AppendEntriesRequest.deserialize(serialized);

        assertEquals(3, deserialized.term());
        assertEquals("leader-1", deserialized.leaderId());
        assertEquals(5, deserialized.prevLogIndex());
        assertEquals(2, deserialized.prevLogTerm());
        assertEquals(4, deserialized.leaderCommit());
        assertEquals(3, deserialized.entries().size());
        assertArrayEquals("cmd-1".getBytes(), deserialized.entries().get(0).command());
        assertArrayEquals("cmd-3".getBytes(), deserialized.entries().get(2).command());
        assertEquals(2, deserialized.entries().get(2).term());
    }

    @Test
    void appendEntriesRequestShouldHandleEmptyEntries() {
        AppendEntriesRequest original = new AppendEntriesRequest(
                1, "leader-1", -1, 0, List.of(), 0);
        byte[] serialized = original.serialize();
        AppendEntriesRequest deserialized = AppendEntriesRequest.deserialize(serialized);

        assertEquals(1, deserialized.term());
        assertTrue(deserialized.entries().isEmpty());
    }

    @Test
    void appendEntriesResponseShouldRoundTrip() {
        AppendEntriesResponse original = new AppendEntriesResponse(3, true, "node-2", 10);
        byte[] serialized = original.serialize();
        AppendEntriesResponse deserialized = AppendEntriesResponse.deserialize(serialized);

        assertEquals(3, deserialized.term());
        assertTrue(deserialized.success());
        assertEquals("node-2", deserialized.followerId());
        assertEquals(10, deserialized.matchIndex());
    }
}
