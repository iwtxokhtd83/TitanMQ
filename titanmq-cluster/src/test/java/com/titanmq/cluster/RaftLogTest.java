package com.titanmq.cluster;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RaftLogTest {

    @Test
    void shouldAppendAndRetrieveEntries() {
        RaftLog log = new RaftLog();
        long idx0 = log.append(1, "cmd-0".getBytes());
        long idx1 = log.append(1, "cmd-1".getBytes());
        long idx2 = log.append(2, "cmd-2".getBytes());

        assertEquals(0, idx0);
        assertEquals(1, idx1);
        assertEquals(2, idx2);
        assertEquals(3, log.size());
        assertEquals(2, log.lastIndex());
        assertEquals(2, log.lastTerm());
    }

    @Test
    void shouldGetEntriesFromIndex() {
        RaftLog log = new RaftLog();
        log.append(1, "a".getBytes());
        log.append(1, "b".getBytes());
        log.append(2, "c".getBytes());

        List<RaftLog.LogEntry> entries = log.getEntries(1);
        assertEquals(2, entries.size());
        assertArrayEquals("b".getBytes(), entries.get(0).command());
        assertArrayEquals("c".getBytes(), entries.get(1).command());
    }

    @Test
    void shouldReturnEmptyForOutOfRangeIndex() {
        RaftLog log = new RaftLog();
        log.append(1, "a".getBytes());

        assertNull(log.getEntry(5));
        assertTrue(log.getEntries(10).isEmpty());
    }

    @Test
    void shouldTruncateConflictingEntries() {
        RaftLog log = new RaftLog();
        log.append(1, "a".getBytes());
        log.append(1, "b".getBytes());
        log.append(1, "c".getBytes());

        // Simulate leader sending entries that conflict at index 1
        List<RaftLog.LogEntry> newEntries = List.of(
                new RaftLog.LogEntry(1, 2, "b-new".getBytes()),
                new RaftLog.LogEntry(2, 2, "c-new".getBytes())
        );

        log.appendEntries(0, 1, newEntries);

        assertEquals(3, log.size());
        assertArrayEquals("a".getBytes(), log.getEntry(0).command());
        assertArrayEquals("b-new".getBytes(), log.getEntry(1).command());
        assertArrayEquals("c-new".getBytes(), log.getEntry(2).command());
        assertEquals(2, log.lastTerm());
    }

    @Test
    void shouldTrackCommitIndex() {
        RaftLog log = new RaftLog();
        log.append(1, "a".getBytes());
        log.append(1, "b".getBytes());

        assertEquals(-1, log.commitIndex());
        log.setCommitIndex(1);
        assertEquals(1, log.commitIndex());
    }

    @Test
    void commitIndexShouldNotExceedLogSize() {
        RaftLog log = new RaftLog();
        log.append(1, "a".getBytes());

        log.setCommitIndex(100);
        assertEquals(0, log.commitIndex()); // Clamped to lastIndex
    }

    @Test
    void emptyLogShouldHaveCorrectDefaults() {
        RaftLog log = new RaftLog();
        assertEquals(-1, log.lastIndex());
        assertEquals(0, log.lastTerm());
        assertEquals(0, log.size());
        assertEquals(-1, log.commitIndex());
    }
}
