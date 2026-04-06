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

        // Commit index should never decrease
        log.setCommitIndex(0);
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

    @Test
    void shouldFindFirstIndexOfTerm() {
        RaftLog log = new RaftLog();
        log.append(1, "a".getBytes());
        log.append(1, "b".getBytes());
        log.append(2, "c".getBytes());
        log.append(2, "d".getBytes());
        log.append(3, "e".getBytes());

        assertEquals(0, log.firstIndexOfTerm(1));
        assertEquals(2, log.firstIndexOfTerm(2));
        assertEquals(4, log.firstIndexOfTerm(3));
        assertEquals(-1, log.firstIndexOfTerm(99));
    }

    @Test
    void shouldReturnTermAtIndex() {
        RaftLog log = new RaftLog();
        log.append(1, "a".getBytes());
        log.append(2, "b".getBytes());

        assertEquals(1, log.termAt(0));
        assertEquals(2, log.termAt(1));
        assertEquals(-1, log.termAt(5));
        assertEquals(-1, log.termAt(-1));
    }

    @Test
    void shouldCheckContainsEntry() {
        RaftLog log = new RaftLog();
        log.append(1, "a".getBytes());
        log.append(2, "b".getBytes());

        assertTrue(log.containsEntry(0, 1));
        assertTrue(log.containsEntry(1, 2));
        assertFalse(log.containsEntry(0, 2)); // Wrong term
        assertFalse(log.containsEntry(5, 1)); // Out of range
        assertTrue(log.containsEntry(-1, 0)); // Empty prefix always matches
    }

    @Test
    void commitIndexShouldNeverDecrease() {
        RaftLog log = new RaftLog();
        log.append(1, "a".getBytes());
        log.append(1, "b".getBytes());
        log.append(1, "c".getBytes());

        log.setCommitIndex(2);
        assertEquals(2, log.commitIndex());

        log.setCommitIndex(1); // Try to decrease
        assertEquals(2, log.commitIndex()); // Should stay at 2

        log.setCommitIndex(0); // Try to decrease further
        assertEquals(2, log.commitIndex()); // Still 2
    }

    @Test
    void shouldTruncateAndReplaceConflictingEntries() {
        RaftLog log = new RaftLog();
        // Simulate entries from old leader at term 1
        log.append(1, "a".getBytes()); // index 0
        log.append(1, "b".getBytes()); // index 1
        log.append(1, "c".getBytes()); // index 2 (uncommitted from crashed leader)
        log.append(1, "d".getBytes()); // index 3 (uncommitted from crashed leader)

        // New leader at term 2 sends entries starting at index 2 with different term
        List<RaftLog.LogEntry> newEntries = List.of(
                new RaftLog.LogEntry(2, 2, "c-new".getBytes()),
                new RaftLog.LogEntry(3, 2, "d-new".getBytes()),
                new RaftLog.LogEntry(4, 2, "e-new".getBytes())
        );

        log.appendEntries(1, 1, newEntries);

        assertEquals(5, log.size());
        assertArrayEquals("a".getBytes(), log.getEntry(0).command());
        assertArrayEquals("b".getBytes(), log.getEntry(1).command());
        assertArrayEquals("c-new".getBytes(), log.getEntry(2).command());
        assertArrayEquals("d-new".getBytes(), log.getEntry(3).command());
        assertArrayEquals("e-new".getBytes(), log.getEntry(4).command());
        assertEquals(2, log.getEntry(2).term()); // New leader's term
    }
}
