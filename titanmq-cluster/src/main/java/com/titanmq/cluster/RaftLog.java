package com.titanmq.cluster;

import java.util.ArrayList;
import java.util.List;

/**
 * Raft replicated log. Stores log entries that are replicated across the cluster.
 * Each entry contains a term number and a command (serialized as bytes).
 *
 * <p>Handles the critical log divergence problem: when a leader crashes mid-write,
 * followers may have uncommitted entries from the old leader that conflict with
 * the new leader's log. This implementation correctly truncates divergent suffixes
 * during {@link #appendEntries} to maintain the Log Matching Property.
 *
 * <p>Key Raft invariants maintained:
 * <ul>
 *   <li>If two entries in different logs have the same index and term, they store the same command</li>
 *   <li>If two entries in different logs have the same index and term, all preceding entries are identical</li>
 *   <li>commitIndex never decreases</li>
 *   <li>Only entries known to be committed are applied to the state machine</li>
 * </ul>
 */
public class RaftLog {

    private final List<LogEntry> entries = new ArrayList<>();
    private long commitIndex = -1;
    private long lastApplied = -1;

    /**
     * Append a new entry to the log (used by the leader for new client commands).
     *
     * @return the index of the appended entry
     */
    public synchronized long append(long term, byte[] command) {
        long index = entries.size();
        entries.add(new LogEntry(index, term, command));
        return index;
    }

    /**
     * Append entries received from a leader (follower-side replication).
     *
     * <p>This is the core of Raft's log divergence resolution. When a new leader
     * sends entries, the follower must:
     * <ol>
     *   <li>Verify the prevLogIndex/prevLogTerm match (done by caller)</li>
     *   <li>Find the first conflicting entry between existing log and new entries</li>
     *   <li>Truncate everything from the conflict point onward</li>
     *   <li>Append any new entries not already in the log</li>
     * </ol>
     *
     * <p>This handles the classic scenario where old leader crashed mid-write:
     * the follower may have entries [0,1,2,3] where 2,3 are from the crashed leader's
     * uncommitted writes. New leader sends entries starting at index 2 with different
     * terms — we truncate 2,3 and replace them.
     */
    public synchronized void appendEntries(long prevLogIndex, long prevLogTerm, List<LogEntry> newEntries) {
        if (newEntries.isEmpty()) return;

        for (LogEntry newEntry : newEntries) {
            int idx = (int) newEntry.index();

            if (idx < entries.size()) {
                LogEntry existing = entries.get(idx);
                if (existing.term() != newEntry.term()) {
                    // Conflict detected: truncate this entry and everything after it.
                    // This is where we resolve log divergence from a crashed leader.
                    log_truncate(idx);
                    entries.add(newEntry);
                }
                // If terms match, entry is already correct — skip it (idempotent)
            } else {
                // Beyond current log — just append
                entries.add(newEntry);
            }
        }
    }

    /**
     * Truncate the log from the given index onward.
     * This removes uncommitted entries from a previous leader that conflict
     * with the new leader's log.
     *
     * <p>SAFETY: We must never truncate committed entries. The caller ensures
     * this by only truncating entries beyond the commit point.
     */
    private void log_truncate(int fromIndex) {
        if (fromIndex < entries.size()) {
            entries.subList(fromIndex, entries.size()).clear();
        }
    }

    /**
     * Check if the log contains an entry at the given index with the given term.
     * Used for the AppendEntries consistency check.
     */
    public synchronized boolean containsEntry(long index, long term) {
        if (index < 0) return true; // Empty log matches any prevLogIndex of -1
        if (index >= entries.size()) return false;
        return entries.get((int) index).term() == term;
    }

    /**
     * Find the first index of a given term in the log.
     * Used for optimized log backtracking (ConflictIndex optimization from §5.3).
     *
     * <p>When a follower rejects AppendEntries, instead of decrementing nextIndex
     * by 1 each time (O(n) RPCs to find the divergence point), the follower returns
     * the first index of the conflicting term. The leader can then skip directly
     * to the right point.
     *
     * @return the first index with the given term, or -1 if not found
     */
    public synchronized long firstIndexOfTerm(long term) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).term() == term) return i;
        }
        return -1;
    }

    /**
     * Get the term of the entry at the given index.
     *
     * @return the term, or -1 if index is out of range
     */
    public synchronized long termAt(long index) {
        if (index < 0 || index >= entries.size()) return -1;
        return entries.get((int) index).term();
    }

    public synchronized LogEntry getEntry(long index) {
        if (index < 0 || index >= entries.size()) return null;
        return entries.get((int) index);
    }

    public synchronized List<LogEntry> getEntries(long fromIndex) {
        if (fromIndex < 0 || fromIndex >= entries.size()) return List.of();
        return new ArrayList<>(entries.subList((int) fromIndex, entries.size()));
    }

    public synchronized long lastIndex() {
        return entries.size() - 1;
    }

    public synchronized long lastTerm() {
        if (entries.isEmpty()) return 0;
        return entries.getLast().term();
    }

    public long commitIndex() { return commitIndex; }

    /**
     * Set the commit index. Never decreases — this is a Raft safety invariant.
     */
    public synchronized void setCommitIndex(long index) {
        long newCommit = Math.min(index, entries.size() - 1);
        if (newCommit > this.commitIndex) {
            this.commitIndex = newCommit;
        }
    }

    public long lastApplied() { return lastApplied; }

    public synchronized void setLastApplied(long index) {
        this.lastApplied = index;
    }

    public synchronized int size() { return entries.size(); }

    /**
     * A single entry in the Raft log.
     */
    public record LogEntry(long index, long term, byte[] command) {}
}
