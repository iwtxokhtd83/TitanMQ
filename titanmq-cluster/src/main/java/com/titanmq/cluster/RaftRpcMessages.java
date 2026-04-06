package com.titanmq.cluster;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Raft RPC message types for leader election and log replication.
 * All messages are serializable to/from ByteBuffer for wire transport.
 */
public final class RaftRpcMessages {

    private RaftRpcMessages() {}

    // ─── VoteRequest (RequestVote RPC) ───

    public record VoteRequest(
            long term,
            String candidateId,
            long lastLogIndex,
            long lastLogTerm
    ) {
        public byte[] serialize() {
            byte[] candidateBytes = candidateId.getBytes();
            ByteBuffer buf = ByteBuffer.allocate(8 + 4 + candidateBytes.length + 8 + 8);
            buf.putLong(term);
            buf.putInt(candidateBytes.length);
            buf.put(candidateBytes);
            buf.putLong(lastLogIndex);
            buf.putLong(lastLogTerm);
            return buf.array();
        }

        public static VoteRequest deserialize(byte[] data) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            long term = buf.getLong();
            int len = buf.getInt();
            byte[] candidateBytes = new byte[len];
            buf.get(candidateBytes);
            long lastLogIndex = buf.getLong();
            long lastLogTerm = buf.getLong();
            return new VoteRequest(term, new String(candidateBytes), lastLogIndex, lastLogTerm);
        }
    }

    // ─── VoteResponse ───

    public record VoteResponse(
            long term,
            boolean voteGranted,
            String voterId
    ) {
        public byte[] serialize() {
            byte[] voterBytes = voterId.getBytes();
            ByteBuffer buf = ByteBuffer.allocate(8 + 1 + 4 + voterBytes.length);
            buf.putLong(term);
            buf.put((byte) (voteGranted ? 1 : 0));
            buf.putInt(voterBytes.length);
            buf.put(voterBytes);
            return buf.array();
        }

        public static VoteResponse deserialize(byte[] data) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            long term = buf.getLong();
            boolean granted = buf.get() == 1;
            int len = buf.getInt();
            byte[] voterBytes = new byte[len];
            buf.get(voterBytes);
            return new VoteResponse(term, granted, new String(voterBytes));
        }
    }

    // ─── AppendEntries RPC ───

    public record AppendEntriesRequest(
            long term,
            String leaderId,
            long prevLogIndex,
            long prevLogTerm,
            List<RaftLog.LogEntry> entries,
            long leaderCommit
    ) {
        public byte[] serialize() {
            byte[] leaderBytes = leaderId.getBytes();
            // Calculate size
            int entriesSize = 4; // count
            for (RaftLog.LogEntry entry : entries) {
                entriesSize += 8 + 8 + 4 + (entry.command() != null ? entry.command().length : 0);
            }
            int totalSize = 8 + 4 + leaderBytes.length + 8 + 8 + entriesSize + 8;
            ByteBuffer buf = ByteBuffer.allocate(totalSize);
            buf.putLong(term);
            buf.putInt(leaderBytes.length);
            buf.put(leaderBytes);
            buf.putLong(prevLogIndex);
            buf.putLong(prevLogTerm);
            buf.putInt(entries.size());
            for (RaftLog.LogEntry entry : entries) {
                buf.putLong(entry.index());
                buf.putLong(entry.term());
                byte[] cmd = entry.command();
                buf.putInt(cmd != null ? cmd.length : 0);
                if (cmd != null) buf.put(cmd);
            }
            buf.putLong(leaderCommit);
            return buf.array();
        }

        public static AppendEntriesRequest deserialize(byte[] data) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            long term = buf.getLong();
            int leaderLen = buf.getInt();
            byte[] leaderBytes = new byte[leaderLen];
            buf.get(leaderBytes);
            long prevLogIndex = buf.getLong();
            long prevLogTerm = buf.getLong();
            int entryCount = buf.getInt();
            List<RaftLog.LogEntry> entries = new ArrayList<>();
            for (int i = 0; i < entryCount; i++) {
                long index = buf.getLong();
                long entryTerm = buf.getLong();
                int cmdLen = buf.getInt();
                byte[] cmd = cmdLen > 0 ? new byte[cmdLen] : null;
                if (cmd != null) buf.get(cmd);
                entries.add(new RaftLog.LogEntry(index, entryTerm, cmd));
            }
            long leaderCommit = buf.getLong();
            return new AppendEntriesRequest(term, new String(leaderBytes),
                    prevLogIndex, prevLogTerm, entries, leaderCommit);
        }
    }

    // ─── AppendEntries Response ───

    /**
     * Response to AppendEntries RPC.
     *
     * <p>When success=false, includes conflict information for optimized backtracking
     * (§5.3 optimization from the Raft paper). Instead of the leader decrementing
     * nextIndex by 1 per rejected RPC (O(n) round trips for n divergent entries),
     * the follower returns:
     * <ul>
     *   <li>conflictIndex: the first index of the conflicting term (or log length if too short)</li>
     *   <li>conflictTerm: the term of the conflicting entry (0 if log is too short)</li>
     * </ul>
     * This lets the leader skip directly to the divergence point in one round trip.
     */
    public record AppendEntriesResponse(
            long term,
            boolean success,
            String followerId,
            long matchIndex,
            long conflictIndex,
            long conflictTerm
    ) {
        public byte[] serialize() {
            byte[] followerBytes = followerId.getBytes();
            ByteBuffer buf = ByteBuffer.allocate(8 + 1 + 4 + followerBytes.length + 8 + 8 + 8);
            buf.putLong(term);
            buf.put((byte) (success ? 1 : 0));
            buf.putInt(followerBytes.length);
            buf.put(followerBytes);
            buf.putLong(matchIndex);
            buf.putLong(conflictIndex);
            buf.putLong(conflictTerm);
            return buf.array();
        }

        public static AppendEntriesResponse deserialize(byte[] data) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            long term = buf.getLong();
            boolean success = buf.get() == 1;
            int len = buf.getInt();
            byte[] followerBytes = new byte[len];
            buf.get(followerBytes);
            long matchIndex = buf.getLong();
            long conflictIndex = buf.getLong();
            long conflictTerm = buf.getLong();
            return new AppendEntriesResponse(term, success, new String(followerBytes),
                    matchIndex, conflictIndex, conflictTerm);
        }
    }
}
