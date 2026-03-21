package com.titanmq.cluster;

import com.titanmq.cluster.RaftRpcMessages.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Full Raft consensus implementation for TitanMQ cluster coordination.
 *
 * <p>Implements the complete Raft protocol including:
 * <ul>
 *   <li>Leader election with RequestVote RPCs</li>
 *   <li>Log replication with AppendEntries RPCs</li>
 *   <li>Commit index advancement via majority agreement</li>
 *   <li>Leader step-down on higher term discovery</li>
 *   <li>Log consistency check and conflict resolution</li>
 * </ul>
 *
 * <p>Unlike Kafka's ZooKeeper/KRaft dependency, TitanMQ embeds Raft directly for
 * zero external dependencies and sub-second failover.
 */
public class RaftNode {

    private static final Logger log = LoggerFactory.getLogger(RaftNode.class);

    private final String nodeId;
    private final List<String> peers;
    private volatile RaftState state = RaftState.FOLLOWER;
    private final AtomicLong currentTerm = new AtomicLong(0);
    private volatile String votedFor = null;
    private volatile String leaderId = null;

    // Raft log
    private final RaftLog raftLog = new RaftLog();

    // Leader state: nextIndex and matchIndex per follower
    private final ConcurrentHashMap<String, Long> nextIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> matchIndex = new ConcurrentHashMap<>();

    // Transport for RPC communication
    private final RaftTransport transport;

    // Scheduling
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private ScheduledFuture<?> electionTimer;
    private ScheduledFuture<?> heartbeatTimer;

    private final long heartbeatIntervalMs = 150;
    private final Random random = new Random();
    private final long baseElectionTimeoutMs;

    // State machine callback
    private final List<StateMachineCallback> stateMachineCallbacks = new CopyOnWriteArrayList<>();
    private final List<LeaderChangeListener> leaderChangeListeners = new CopyOnWriteArrayList<>();

    public RaftNode(String nodeId, List<String> peers, RaftTransport transport) {
        this.nodeId = nodeId;
        this.peers = new ArrayList<>(peers);
        this.transport = transport;
        this.baseElectionTimeoutMs = 300 + random.nextInt(200); // 300-500ms
    }

    /**
     * Convenience constructor for single-node or test scenarios.
     */
    public RaftNode(String nodeId, List<String> peers) {
        this(nodeId, peers, new InMemoryRaftTransport(nodeId));
    }

    public void start() {
        transport.start();
        if (transport instanceof InMemoryRaftTransport) {
            InMemoryRaftTransport.registerNode(nodeId, this);
        }
        resetElectionTimer();
        log.info("Raft node {} started as FOLLOWER, term={}", nodeId, currentTerm.get());
    }

    public void stop() {
        if (electionTimer != null) electionTimer.cancel(true);
        if (heartbeatTimer != null) heartbeatTimer.cancel(true);
        scheduler.shutdownNow();
        transport.stop();
        log.info("Raft node {} stopped", nodeId);
    }

    // ─── Leader Election ───

    /**
     * Called when election timeout fires. Transition to CANDIDATE and request votes.
     */
    private synchronized void startElection() {
        state = RaftState.CANDIDATE;
        long term = currentTerm.incrementAndGet();
        votedFor = nodeId;
        leaderId = null;
        log.info("Node {} starting election for term {}", nodeId, term);

        int clusterSize = peers.size() + 1;
        int votesNeeded = clusterSize / 2 + 1;
        AtomicInteger votesReceived = new AtomicInteger(1); // Vote for self

        if (peers.isEmpty()) {
            // Single-node cluster: win immediately
            becomeLeader();
            return;
        }

        VoteRequest request = new VoteRequest(
                term, nodeId, raftLog.lastIndex(), raftLog.lastTerm());

        for (String peerId : peers) {
            transport.sendVoteRequest(peerId, request)
                    .orTimeout(200, TimeUnit.MILLISECONDS)
                    .whenComplete((response, ex) -> {
                        if (ex != null) {
                            log.debug("VoteRequest to {} failed: {}", peerId, ex.getMessage());
                            return;
                        }
                        handleVoteResponse(response, term, votesReceived, votesNeeded);
                    });
        }

        // Reset election timer in case we don't win
        resetElectionTimer();
    }

    private synchronized void handleVoteResponse(VoteResponse response, long electionTerm,
                                                   AtomicInteger votesReceived, int votesNeeded) {
        if (response.term() > currentTerm.get()) {
            stepDown(response.term());
            return;
        }

        if (state != RaftState.CANDIDATE || currentTerm.get() != electionTerm) {
            return; // Stale response
        }

        if (response.voteGranted()) {
            int votes = votesReceived.incrementAndGet();
            log.debug("Node {} received vote from {}, total={}/{}", nodeId, response.voterId(), votes, votesNeeded);
            if (votes >= votesNeeded) {
                becomeLeader();
            }
        }
    }

    /**
     * Handle an incoming VoteRequest from a candidate.
     */
    public synchronized VoteResponse handleVoteRequest(VoteRequest request) {
        if (request.term() > currentTerm.get()) {
            stepDown(request.term());
        }

        boolean grantVote = false;
        if (request.term() >= currentTerm.get()) {
            if (votedFor == null || votedFor.equals(request.candidateId())) {
                // Check log is at least as up-to-date
                if (request.lastLogTerm() > raftLog.lastTerm() ||
                        (request.lastLogTerm() == raftLog.lastTerm() &&
                                request.lastLogIndex() >= raftLog.lastIndex())) {
                    grantVote = true;
                    votedFor = request.candidateId();
                    resetElectionTimer();
                    log.debug("Node {} granted vote to {} for term {}", nodeId, request.candidateId(), request.term());
                }
            }
        }

        return new VoteResponse(currentTerm.get(), grantVote, nodeId);
    }

    private void becomeLeader() {
        if (state == RaftState.LEADER) return;
        state = RaftState.LEADER;
        leaderId = nodeId;
        log.info("Node {} became LEADER for term {}", nodeId, currentTerm.get());

        // Initialize leader state
        long lastIdx = raftLog.lastIndex() + 1;
        for (String peerId : peers) {
            nextIndex.put(peerId, lastIdx);
            matchIndex.put(peerId, -1L);
        }

        // Cancel election timer, start heartbeats
        if (electionTimer != null) electionTimer.cancel(false);
        heartbeatTimer = scheduler.scheduleAtFixedRate(
                this::sendHeartbeats, 0, heartbeatIntervalMs, TimeUnit.MILLISECONDS);

        leaderChangeListeners.forEach(l -> l.onLeaderChange(nodeId, currentTerm.get()));
    }

    private void stepDown(long newTerm) {
        log.info("Node {} stepping down, term {} -> {}", nodeId, currentTerm.get(), newTerm);
        currentTerm.set(newTerm);
        state = RaftState.FOLLOWER;
        votedFor = null;
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel(false);
            heartbeatTimer = null;
        }
        resetElectionTimer();
    }

    // ─── Log Replication ───

    /**
     * Send heartbeats / AppendEntries to all followers.
     */
    private void sendHeartbeats() {
        if (state != RaftState.LEADER) return;

        for (String peerId : peers) {
            sendAppendEntriesToPeer(peerId);
        }
    }

    private void sendAppendEntriesToPeer(String peerId) {
        long nextIdx = nextIndex.getOrDefault(peerId, 0L);
        long prevIdx = nextIdx - 1;
        long prevTerm = 0;
        if (prevIdx >= 0) {
            RaftLog.LogEntry prevEntry = raftLog.getEntry(prevIdx);
            if (prevEntry != null) prevTerm = prevEntry.term();
        }

        List<RaftLog.LogEntry> entries = raftLog.getEntries(nextIdx);

        AppendEntriesRequest request = new AppendEntriesRequest(
                currentTerm.get(), nodeId, prevIdx, prevTerm, entries, raftLog.commitIndex());

        transport.sendAppendEntries(peerId, request)
                .orTimeout(200, TimeUnit.MILLISECONDS)
                .whenComplete((response, ex) -> {
                    if (ex != null) {
                        log.debug("AppendEntries to {} failed: {}", peerId, ex.getMessage());
                        return;
                    }
                    handleAppendEntriesResponse(peerId, response, entries.size());
                });
    }

    private synchronized void handleAppendEntriesResponse(String peerId,
                                                            AppendEntriesResponse response,
                                                            int entriesSent) {
        if (response.term() > currentTerm.get()) {
            stepDown(response.term());
            return;
        }

        if (state != RaftState.LEADER) return;

        if (response.success()) {
            // Update nextIndex and matchIndex
            long newMatchIndex = response.matchIndex();
            matchIndex.put(peerId, newMatchIndex);
            nextIndex.put(peerId, newMatchIndex + 1);

            // Try to advance commit index
            advanceCommitIndex();
        } else {
            // Decrement nextIndex and retry
            long currentNext = nextIndex.getOrDefault(peerId, 1L);
            nextIndex.put(peerId, Math.max(0, currentNext - 1));
            log.debug("AppendEntries rejected by {}, decrementing nextIndex to {}", peerId, currentNext - 1);
        }
    }

    /**
     * Handle an incoming AppendEntries RPC from the leader.
     */
    public synchronized AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
        if (request.term() < currentTerm.get()) {
            return new AppendEntriesResponse(currentTerm.get(), false, nodeId, raftLog.lastIndex());
        }

        // Valid leader — reset election timer
        if (request.term() > currentTerm.get()) {
            currentTerm.set(request.term());
            votedFor = null;
        }
        state = RaftState.FOLLOWER;
        leaderId = request.leaderId();
        resetElectionTimer();

        // Log consistency check
        if (request.prevLogIndex() >= 0) {
            RaftLog.LogEntry prevEntry = raftLog.getEntry(request.prevLogIndex());
            if (prevEntry == null || prevEntry.term() != request.prevLogTerm()) {
                return new AppendEntriesResponse(currentTerm.get(), false, nodeId, raftLog.lastIndex());
            }
        }

        // Append entries
        if (!request.entries().isEmpty()) {
            raftLog.appendEntries(request.prevLogIndex(), request.prevLogTerm(), request.entries());
        }

        // Update commit index
        if (request.leaderCommit() > raftLog.commitIndex()) {
            raftLog.setCommitIndex(Math.min(request.leaderCommit(), raftLog.lastIndex()));
            applyCommittedEntries();
        }

        return new AppendEntriesResponse(currentTerm.get(), true, nodeId, raftLog.lastIndex());
    }

    /**
     * Advance the commit index based on majority replication.
     */
    private void advanceCommitIndex() {
        for (long n = raftLog.lastIndex(); n > raftLog.commitIndex(); n--) {
            RaftLog.LogEntry entry = raftLog.getEntry(n);
            if (entry == null || entry.term() != currentTerm.get()) continue;

            // Count replicas (including self)
            int replicaCount = 1;
            for (String peerId : peers) {
                if (matchIndex.getOrDefault(peerId, -1L) >= n) {
                    replicaCount++;
                }
            }

            int majority = (peers.size() + 1) / 2 + 1;
            if (replicaCount >= majority) {
                raftLog.setCommitIndex(n);
                applyCommittedEntries();
                break;
            }
        }
    }

    /**
     * Apply committed but not yet applied entries to the state machine.
     */
    private void applyCommittedEntries() {
        while (raftLog.lastApplied() < raftLog.commitIndex()) {
            long nextApply = raftLog.lastApplied() + 1;
            RaftLog.LogEntry entry = raftLog.getEntry(nextApply);
            if (entry != null) {
                for (StateMachineCallback callback : stateMachineCallbacks) {
                    callback.apply(entry.index(), entry.command());
                }
                raftLog.setLastApplied(nextApply);
            }
        }
    }

    // ─── Client API ───

    /**
     * Submit a command to the Raft cluster. Only succeeds on the leader.
     *
     * @return CompletableFuture that completes when the command is committed
     */
    public CompletableFuture<Long> submitCommand(byte[] command) {
        if (state != RaftState.LEADER) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Not the leader. Current leader: " + leaderId));
        }

        long index = raftLog.append(currentTerm.get(), command);
        matchIndex.put(nodeId, index); // Leader has it

        // Immediately replicate to followers
        sendHeartbeats();

        // Return future that completes when committed
        CompletableFuture<Long> future = new CompletableFuture<>();
        scheduler.scheduleAtFixedRate(() -> {
            if (raftLog.commitIndex() >= index) {
                future.complete(index);
                throw new CancellationException("Done"); // Cancel the scheduled task
            }
            if (state != RaftState.LEADER) {
                future.completeExceptionally(new IllegalStateException("Lost leadership"));
                throw new CancellationException("Done");
            }
        }, 10, 10, TimeUnit.MILLISECONDS);

        return future;
    }

    // ─── Timer Management ───

    private void resetElectionTimer() {
        if (electionTimer != null) electionTimer.cancel(false);
        long timeout = baseElectionTimeoutMs + random.nextInt(200);
        electionTimer = scheduler.schedule(this::startElection, timeout, TimeUnit.MILLISECONDS);
    }

    // ─── Callbacks ───

    public void addLeaderChangeListener(LeaderChangeListener listener) {
        leaderChangeListeners.add(listener);
    }

    public void addStateMachineCallback(StateMachineCallback callback) {
        stateMachineCallbacks.add(callback);
    }

    // ─── Accessors ───

    public String nodeId() { return nodeId; }
    public RaftState state() { return state; }
    public long currentTerm() { return currentTerm.get(); }
    public String leaderId() { return leaderId; }
    public boolean isLeader() { return state == RaftState.LEADER; }
    public RaftLog raftLog() { return raftLog; }

    // ─── Callback Interfaces ───

    @FunctionalInterface
    public interface LeaderChangeListener {
        void onLeaderChange(String newLeaderId, long term);
    }

    @FunctionalInterface
    public interface StateMachineCallback {
        void apply(long index, byte[] command);
    }
}
