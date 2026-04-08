package com.titanmq.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent storage for Raft state that must survive restarts.
 *
 * <p>Per the Raft paper (§5.2), the following must be persisted before responding to RPCs:
 * <ul>
 *   <li>currentTerm — the latest term the server has seen</li>
 *   <li>votedFor — the candidate that received our vote in the current term</li>
 *   <li>log entries — the replicated log</li>
 * </ul>
 *
 * <p>Without persistence, a restarted node could:
 * <ul>
 *   <li>Vote for two different candidates in the same term (violating election safety)</li>
 *   <li>Lose committed entries (violating state machine safety)</li>
 *   <li>Cause split-brain by starting a new election at term 0</li>
 * </ul>
 *
 * <p>File layout:
 * <pre>
 *   {dataDir}/raft-meta    — currentTerm (8B) + votedFor length (4B) + votedFor (NB)
 *   {dataDir}/raft-log     — sequential log entries: [8B index][8B term][4B cmdLen][NB cmd]
 * </pre>
 */
public class RaftPersistence {

    private static final Logger log = LoggerFactory.getLogger(RaftPersistence.class);

    private final Path metaFile;
    private final Path logFile;

    public RaftPersistence(Path dataDir) {
        dataDir.toFile().mkdirs();
        this.metaFile = dataDir.resolve("raft-meta");
        this.logFile = dataDir.resolve("raft-log");
    }

    // ─── Meta (currentTerm + votedFor) ───

    /**
     * Persist currentTerm and votedFor atomically.
     * Called before responding to any RPC that changes these values.
     */
    public void saveMeta(long currentTerm, String votedFor) throws IOException {
        byte[] votedForBytes = votedFor != null ? votedFor.getBytes() : new byte[0];
        ByteBuffer buf = ByteBuffer.allocate(8 + 4 + votedForBytes.length);
        buf.putLong(currentTerm);
        buf.putInt(votedForBytes.length);
        if (votedForBytes.length > 0) buf.put(votedForBytes);
        buf.flip();

        // Write to temp file then rename for atomicity
        Path tmpFile = metaFile.resolveSibling("raft-meta.tmp");
        try (FileChannel ch = FileChannel.open(tmpFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(buf);
            ch.force(true);
        }
        Files.move(tmpFile, metaFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Load persisted currentTerm and votedFor.
     *
     * @return [currentTerm, votedFor] or null if no persisted state
     */
    public MetaState loadMeta() throws IOException {
        if (!Files.exists(metaFile)) return null;

        byte[] data = Files.readAllBytes(metaFile);
        if (data.length < 12) return null;

        ByteBuffer buf = ByteBuffer.wrap(data);
        long currentTerm = buf.getLong();
        int votedForLen = buf.getInt();
        String votedFor = null;
        if (votedForLen > 0) {
            byte[] votedForBytes = new byte[votedForLen];
            buf.get(votedForBytes);
            votedFor = new String(votedForBytes);
        }
        return new MetaState(currentTerm, votedFor);
    }

    // ─── Log Entries ───

    /**
     * Append a single log entry to the persistent log.
     */
    public void appendLogEntry(RaftLog.LogEntry entry) throws IOException {
        byte[] cmd = entry.command();
        int cmdLen = cmd != null ? cmd.length : 0;
        ByteBuffer buf = ByteBuffer.allocate(8 + 8 + 4 + cmdLen);
        buf.putLong(entry.index());
        buf.putLong(entry.term());
        buf.putInt(cmdLen);
        if (cmd != null) buf.put(cmd);
        buf.flip();

        try (FileChannel ch = FileChannel.open(logFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(buf);
            ch.force(false);
        }
    }

    /**
     * Load all persisted log entries.
     */
    public List<RaftLog.LogEntry> loadLogEntries() throws IOException {
        List<RaftLog.LogEntry> entries = new ArrayList<>();
        if (!Files.exists(logFile)) return entries;

        try (FileChannel ch = FileChannel.open(logFile, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            long pos = 0;

            while (pos + 20 <= fileSize) { // minimum: 8+8+4 = 20 bytes
                ByteBuffer header = ByteBuffer.allocate(20);
                ch.read(header, pos);
                header.flip();

                long index = header.getLong();
                long term = header.getLong();
                int cmdLen = header.getInt();

                if (cmdLen < 0 || pos + 20 + cmdLen > fileSize) break;

                byte[] cmd = null;
                if (cmdLen > 0) {
                    cmd = new byte[cmdLen];
                    ByteBuffer cmdBuf = ByteBuffer.wrap(cmd);
                    ch.read(cmdBuf, pos + 20);
                }

                entries.add(new RaftLog.LogEntry(index, term, cmd));
                pos += 20 + cmdLen;
            }
        }

        log.info("Loaded {} Raft log entries from {}", entries.size(), logFile);
        return entries;
    }

    /**
     * Truncate the persistent log from the given index onward.
     * Used when log divergence is detected.
     */
    public void truncateLogFrom(long fromIndex) throws IOException {
        if (!Files.exists(logFile)) return;

        // Find the file position of the entry at fromIndex
        try (FileChannel ch = FileChannel.open(logFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long fileSize = ch.size();
            long pos = 0;

            while (pos + 20 <= fileSize) {
                ByteBuffer header = ByteBuffer.allocate(20);
                ch.read(header, pos);
                header.flip();

                long index = header.getLong();
                header.getLong(); // term
                int cmdLen = header.getInt();

                if (index >= fromIndex) {
                    ch.truncate(pos);
                    log.info("Truncated Raft log at index {} (file position {})", fromIndex, pos);
                    return;
                }

                pos += 20 + Math.max(0, cmdLen);
            }
        }
    }

    /**
     * Rewrite the entire log file (used after compaction or major truncation).
     */
    public void rewriteLog(List<RaftLog.LogEntry> entries) throws IOException {
        Path tmpFile = logFile.resolveSibling("raft-log.tmp");
        try (FileChannel ch = FileChannel.open(tmpFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (RaftLog.LogEntry entry : entries) {
                byte[] cmd = entry.command();
                int cmdLen = cmd != null ? cmd.length : 0;
                ByteBuffer buf = ByteBuffer.allocate(8 + 8 + 4 + cmdLen);
                buf.putLong(entry.index());
                buf.putLong(entry.term());
                buf.putInt(cmdLen);
                if (cmd != null) buf.put(cmd);
                buf.flip();
                ch.write(buf);
            }
            ch.force(true);
        }
        Files.move(tmpFile, logFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    public record MetaState(long currentTerm, String votedFor) {}
}
