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
 *   <li>Leader election with RequestVote RPCs and log up-to-date checks</li>
 *   <li>Log replication with AppendEntries RPCs</li>
 *   <li>Commit index advancement via majority agreement</li>
 *   <li>Leader step-down on higher term discovery</li>
 *   <li>Log divergence resolution on leader change (follower truncation)</li>
 *   <li>No-op entry on leader election for liveness</li>
 *   <li>Optimized log backtracking with ConflictTerm/ConflictIndex</li>
 * </ul>
 *
 * <h3>Leader Crash Mid-Write Safety</h3>
 * <p>When a leader crashes after appending to its local log but before replicating
 * to a majority, the entry is NOT committed. On leader change:
 * <ol>
 *   <li>The new leader's log is authoritative (it won the election, so its log
 *       is at least as up-to-date as a majority)</li>
 *   <li>Followers with divergent entries (from the crashed leader) will have those
 *       entries truncated when the new leader's AppendEntries RPCs arrive</li>
 *   <li>The new leader appends a no-op entry in its own term to establish commit
 *       authority — this is required because Raft only commits entries from the
 *       current term (§5.4.2), and previous-term entries are committed indirectly</li>
 * </ol>
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

    // Pending client commands waiting for commit
    private final ConcurrentHashMap<Long, CompletableFuture<Long>> pendingCommands = new ConcurrentHashMap<>();

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

        // Fail all pending commands
        pendingCommands.values().forEach(f ->
                f.completeExceptionally(new IllegalStateException("Node shutting down")));
        pendingCommands.clear();

        log.info("Raft node {} stopped", nodeId);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Leader Election
    // ═══════════════════════════════════════════════════════════════

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
     *
     * <p>Grant vote only if:
     * <ol>
     *   <li>Candidate's term >= our term</li>
     *   <li>We haven't voted for someone else in this term</li>
     *   <li>Candidate's log is at least as up-to-date as ours (§5.4.1):
     *       compare last log term first, then last log index</li>
     * </ol>
     */
    public synchronized VoteResponse handleVoteRequest(VoteRequest request) {
        if (request.term() > currentTerm.get()) {
            stepDown(request.term());
        }

        boolean grantVote = false;
        if (request.term() >= currentTerm.get()) {
            if (votedFor == null || votedFor.equals(request.candidateId())) {
                // Log up-to-date check (§5.4.1)
                boolean logIsUpToDate =
                        request.lastLogTerm() > raftLog.lastTerm() ||
                        (request.lastLogTerm() == raftLog.lastTerm() &&
                                request.lastLogIndex() >= raftLog.lastIndex());

                if (logIsUpToDate) {
                    grantVote = true;
                    votedFor = request.candidateId();
                    resetElectionTimer();
                    log.debug("Node {} granted vote to {} for term {}",
                            nodeId, request.candidateId(), request.term());
                }
            }
        }

        return new VoteResponse(currentTerm.get(), grantVote, nodeId);
    }

    /**
     * Transition to LEADER state.
     *
     * <p>On becoming leader, we:
     * <ol>
     *   <li>Initialize nextIndex for each follower to our last log index + 1</li>
     *   <li>Initialize matchIndex for each follower to -1</li>
     *   <li>Append a no-op entry in the current term (§8, dissertation §6.4)</li>
     *   <li>Start sending heartbeats immediately</li>
     * </ol>
     *
     * <p>The no-op entry is critical for liveness: Raft only commits entries from
     * the current term. Without it, a new leader with uncommitted entries from
     * previous terms cannot advance the commit index until a new client command
     * arrives. The no-op ensures the leader can commit previous-term entries
     * indirectly by committing the no-op (which drags along all preceding entries).
     */
    private void becomeLeader() {
        if (state == RaftState.LEADER) return;
        state = RaftState.LEADER;
        leaderId = nodeId;
        log.info("Node {} became LEADER for term {}", nodeId, currentTerm.get());

        // Initialize leader volatile state
        long lastIdx = raftLog.lastIndex() + 1;
        for (String peerId : peers) {
            nextIndex.put(peerId, lastIdx);
            matchIndex.put(peerId, -1L);
        }

        // Append no-op entry to establish commit authority for this term.
        // This is the Raft solution to the "previous-term commit" problem:
        // entries from earlier terms are only committed once a current-term
        // entry following them is committed.
        raftLog.append(currentTerm.get(), null); // null command = no-op
        matchIndex.put(nodeId, raftLog.lastIndex());

        // Cancel election timer, start heartbeats
        if (electionTimer != null) electionTimer.cancel(false);
        heartbeatTimer = scheduler.scheduleAtFixedRate(
                this::sendHeartbeats, 0, heartbeatIntervalMs, TimeUnit.MILLISECONDS);

        leaderChangeListeners.forEach(l -> l.onLeaderChange(nodeId, currentTerm.get()));
    }

    /**
     * Step down to FOLLOWER on discovering a higher term.
     * Fails all pending client commands since we're no longer the leader.
     */
    private void stepDown(long newTerm) {
        log.info("Node {} stepping down, term {} -> {}", nodeId, currentTerm.get(), newTerm);
        currentTerm.set(newTerm);
        state = RaftState.FOLLOWER;
        votedFor = null;
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel(false);
            heartbeatTimer = null;
        }

        // Fail all pending client commands — they may or may not have been committed.
        // Clients should retry on the new leader.
        pendingCommands.values().forEach(f ->
                f.completeExceptionally(new IllegalStateException(
                        "Leadership lost during commit. Retry on new leader.")));
        pendingCommands.clear();

        resetElectionTimer();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Log Replication
    // ═══════════════════════════════════════════════════════════════

    /**
     * Send AppendEntries RPCs to all followers.
     * Called periodically as heartbeats and immediately after new entries are appended.
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
            prevTerm = raftLog.termAt(prevIdx);
            if (prevTerm == -1) prevTerm = 0;
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
                    handleAppendEntriesResponse(peerId, response);
                });
    }

    private synchronized void handleAppendEntriesResponse(String peerId,
                                                            AppendEntriesResponse response) {
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
            // Optimized backtracking using ConflictTerm/ConflictIndex (§5.3 optimization).
            // Instead of decrementing nextIndex by 1 each time (which is O(n) RPCs
            // for n divergent entries), use the conflict info from the follower.
            long conflictIndex = response.conflictIndex();
            long conflictTerm = response.conflictTerm();

            if (conflictTerm > 0) {
                // Follower has a conflicting entry. Find the last entry in our log
                // with that term. If we have it, set nextIndex to the entry after it.
                // If we don't have that term at all, use the follower's conflictIndex.
                long ourLastOfTerm = -1;
                for (long i = raftLog.lastIndex(); i >= 0; i--) {
                    if (raftLog.termAt(i) == conflictTerm) {
                        ourLastOfTerm = i;
                        break;
                    }
                }
                if (ourLastOfTerm >= 0) {
                    nextIndex.put(peerId, ourLastOfTerm + 1);
                } else {
                    nextIndex.put(peerId, conflictIndex);
                }
            } else {
                // Follower's log is shorter than prevLogIndex — jump to their log end
                nextIndex.put(peerId, Math.max(0, conflictIndex));
            }

            log.debug("AppendEntries rejected by {}, adjusted nextIndex to {}",
                    peerId, nextIndex.get(peerId));
        }
    }

    /**
     * Handle an incoming AppendEntries RPC from the leader.
     *
     * <p>This is where log divergence from a crashed leader gets resolved.
     * If our log has entries that conflict with the leader's, they are truncated
     * and replaced. This is safe because:
     * <ul>
     *   <li>Conflicting entries were never committed (the leader that created them
     *       crashed before achieving majority replication)</li>
     *   <li>The new leader's log is authoritative (it won the election with a
     *       log at least as up-to-date as a majority)</li>
     * </ul>
     */
    public synchronized AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
        // §1: Reply false if term < currentTerm
        if (request.term() < currentTerm.get()) {
            return new AppendEntriesResponse(currentTerm.get(), false, nodeId,
                    raftLog.lastIndex(), 0, 0);
        }

        // Valid leader — update state
        if (request.term() > currentTerm.get()) {
            currentTerm.set(request.term());
            votedFor = null;
        }
        state = RaftState.FOLLOWER;
        leaderId = request.leaderId();
        resetElectionTimer();

        // §2: Log consistency check — verify prevLogIndex/prevLogTerm match
        if (request.prevLogIndex() >= 0) {
            if (request.prevLogIndex() >= raftLog.size()) {
                // Our log is too short — return conflict info for fast backtracking
                return new AppendEntriesResponse(currentTerm.get(), false, nodeId,
                        raftLog.lastIndex(), raftLog.size(), 0);
            }

            long existingTerm = raftLog.termAt(request.prevLogIndex());
            if (existingTerm != request.prevLogTerm()) {
                // Conflict: return the term of the conflicting entry and the first
                // index of that term, so the leader can skip back efficiently
                long conflictTerm = existingTerm;
                long conflictIndex = raftLog.firstIndexOfTerm(conflictTerm);
                return new AppendEntriesResponse(currentTerm.get(), false, nodeId,
                        raftLog.lastIndex(), conflictIndex, conflictTerm);
            }
        }

        // §3 & §4: Append new entries (with conflict truncation handled by RaftLog)
        if (!request.entries().isEmpty()) {
            raftLog.appendEntries(request.prevLogIndex(), request.prevLogTerm(), request.entries());
        }

        // §5: Update commit index
        if (request.leaderCommit() > raftLog.commitIndex()) {
            raftLog.setCommitIndex(Math.min(request.leaderCommit(), raftLog.lastIndex()));
            applyCommittedEntries();
        }

        return new AppendEntriesResponse(currentTerm.get(), true, nodeId,
                raftLog.lastIndex(), 0, 0);
    }

    /**
     * Advance the commit index based on majority replication.
     *
     * <p>CRITICAL SAFETY PROPERTY (§5.4.2): A leader only commits entries from
     * its own term. Entries from previous terms are committed indirectly when
     * a current-term entry following them is committed. This prevents the
     * scenario where a leader commits a previous-term entry, crashes, and a
     * new leader overwrites it (the Figure 8 problem from the Raft paper).
     *
     * <p>This is why {@link #becomeLeader()} appends a no-op entry — without it,
     * a new leader with only previous-term entries could never advance the commit index.
     */
    private void advanceCommitIndex() {
        long oldCommitIndex = raftLog.commitIndex();

        for (long n = raftLog.lastIndex(); n > raftLog.commitIndex(); n--) {
            // Only commit entries from the current term (§5.4.2)
            long entryTerm = raftLog.termAt(n);
            if (entryTerm != currentTerm.get()) continue;

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
                completePendingCommands(oldCommitIndex, n);
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
            if (entry != null && entry.command() != null) { // Skip no-op entries
                for (StateMachineCallback callback : stateMachineCallbacks) {
                    callback.apply(entry.index(), entry.command());
                }
            }
            raftLog.setLastApplied(nextApply);
        }
    }

    /**
     * Complete pending client command futures for newly committed entries.
     */
    private void completePendingCommands(long oldCommitIndex, long newCommitIndex) {
        for (long i = oldCommitIndex + 1; i <= newCommitIndex; i++) {
            CompletableFuture<Long> future = pendingCommands.remove(i);
            if (future != null) {
                future.complete(i);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Client API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Submit a command to the Raft cluster. Only succeeds on the leader.
     *
     * <p>The returned future completes when the command is committed by a majority.
     * If this node loses leadership before the command is committed, the future
     * completes exceptionally. The client should retry on the new leader.
     *
     * <p>Note: a command that was appended but not committed when leadership is lost
     * may or may not survive. The client must use idempotency keys to handle this.
     *
     * @return CompletableFuture that completes with the log index when committed
     */
    public CompletableFuture<Long> submitCommand(byte[] command) {
        if (state != RaftState.LEADER) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Not the leader. Current leader: " + leaderId));
        }

        long index;
        synchronized (this) {
            index = raftLog.append(currentTerm.get(), command);
            matchIndex.put(nodeId, index);
        }

        CompletableFuture<Long> future = new CompletableFuture<>();
        pendingCommands.put(index, future);

        // Immediately replicate to followers
        sendHeartbeats();

        // For single-node clusters, commit immediately
        if (peers.isEmpty()) {
            advanceCommitIndex();
        }

        return future;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Timer Management
    // ═══════════════════════════════════════════════════════════════

    private void resetElectionTimer() {
        if (electionTimer != null) electionTimer.cancel(false);
        long timeout = baseElectionTimeoutMs + random.nextInt(200);
        electionTimer = scheduler.schedule(this::startElection, timeout, TimeUnit.MILLISECONDS);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Callbacks & Accessors
    // ═══════════════════════════════════════════════════════════════

    public void addLeaderChangeListener(LeaderChangeListener listener) {
        leaderChangeListeners.add(listener);
    }

    public void addStateMachineCallback(StateMachineCallback callback) {
        stateMachineCallbacks.add(callback);
    }

    public String nodeId() { return nodeId; }
    public RaftState state() { return state; }
    public long currentTerm() { return currentTerm.get(); }
    public String leaderId() { return leaderId; }
    public boolean isLeader() { return state == RaftState.LEADER; }
    public RaftLog raftLog() { return raftLog; }

    @FunctionalInterface
    public interface LeaderChangeListener {
        void onLeaderChange(String newLeaderId, long term);
    }

    @FunctionalInterface
    public interface StateMachineCallback {
        void apply(long index, byte[] command);
    }
}
