package com.titanmq.cluster;

import java.util.ArrayList;
import java.util.List;

/**
 * Raft replicated log. Stores log entries that are replicated across the cluster.
 * Each entry contains a term number and a command (serialized as bytes).
 */
public class RaftLog {

    private final List<LogEntry> entries = new ArrayList<>();
    private long commitIndex = -1;
    private long lastApplied = -1;

    /**
     * Append a new entry to the log.
     *
     * @return the index of the appended entry
     */
    public synchronized long append(long term, byte[] command) {
        long index = entries.size();
        entries.add(new LogEntry(index, term, command));
        return index;
    }

    /**
     * Append entries received from a leader (for replication).
     * Truncates conflicting entries if necessary.
     */
    public synchronized void appendEntries(long prevLogIndex, long prevLogTerm, List<LogEntry> newEntries) {
        // Truncate conflicting entries
        if (prevLogIndex >= 0 && prevLogIndex < entries.size()) {
            LogEntry existing = entries.get((int) prevLogIndex);
            if (existing.term() != prevLogTerm) {
                // Conflict: remove this entry and everything after it
                entries.subList((int) prevLogIndex, entries.size()).clear();
            }
        }

        // Append new entries that don't already exist
        for (LogEntry entry : newEntries) {
            int idx = (int) entry.index();
            if (idx >= entries.size()) {
                entries.add(entry);
            } else if (entries.get(idx).term() != entry.term()) {
                entries.set(idx, entry);
            }
        }
    }

    public synchronized LogEntry getEntry(long index) {
        if (index < 0 || index >= entries.size()) return null;
        return entries.get((int) index);
    }

    public synchronized List<LogEntry> getEntries(long fromIndex) {
        if (fromIndex >= entries.size()) return List.of();
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

    public synchronized void setCommitIndex(long index) {
        this.commitIndex = Math.min(index, entries.size() - 1);
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
