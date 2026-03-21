package com.titanmq.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simplified Raft consensus implementation for TitanMQ cluster coordination.
 *
 * <p>Handles leader election and log replication for metadata and partition leadership.
 * Unlike Kafka's ZooKeeper/KRaft dependency, TitanMQ embeds Raft directly for:
 * <ul>
 *   <li>Zero external dependencies for clustering</li>
 *   <li>Faster failover (sub-second leader election)</li>
 *   <li>Consistent metadata replication</li>
 * </ul>
 */
public class RaftNode {

    private static final Logger log = LoggerFactory.getLogger(RaftNode.class);

    private final String nodeId;
    private final List<String> peers;
    private volatile RaftState state = RaftState.FOLLOWER;
    private final AtomicLong currentTerm = new AtomicLong(0);
    private volatile String votedFor = null;
    private volatile String leaderId = null;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private ScheduledFuture<?> electionTimer;
    private ScheduledFuture<?> heartbeatTimer;

    private final long electionTimeoutMs;
    private final long heartbeatIntervalMs = 150;
    private final Random random = new Random();

    // Callbacks
    private final List<LeaderChangeListener> leaderChangeListeners = new CopyOnWriteArrayList<>();

    public RaftNode(String nodeId, List<String> peers) {
        this.nodeId = nodeId;
        this.peers = new ArrayList<>(peers);
        this.electionTimeoutMs = 300 + random.nextInt(200); // 300-500ms randomized
    }

    public void start() {
        resetElectionTimer();
        log.info("Raft node {} started as FOLLOWER, term={}", nodeId, currentTerm.get());
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    /**
     * Called when election timeout fires — transition to candidate and start election.
     */
    private void startElection() {
        state = RaftState.CANDIDATE;
        long term = currentTerm.incrementAndGet();
        votedFor = nodeId;
        log.info("Node {} starting election for term {}", nodeId, term);

        int votesReceived = 1; // Vote for self
        int votesNeeded = (peers.size() + 1) / 2 + 1;

        // In a real implementation, this would send VoteRequest RPCs to peers
        // For now, simulate immediate win in single-node mode
        if (peers.isEmpty() || votesReceived >= votesNeeded) {
            becomeLeader();
        } else {
            resetElectionTimer();
        }
    }

    private void becomeLeader() {
        state = RaftState.LEADER;
        leaderId = nodeId;
        log.info("Node {} became LEADER for term {}", nodeId, currentTerm.get());

        // Cancel election timer, start heartbeats
        if (electionTimer != null) electionTimer.cancel(false);
        heartbeatTimer = scheduler.scheduleAtFixedRate(
                this::sendHeartbeats, 0, heartbeatIntervalMs, TimeUnit.MILLISECONDS);

        leaderChangeListeners.forEach(l -> l.onLeaderChange(nodeId, currentTerm.get()));
    }

    private void sendHeartbeats() {
        // In a real implementation, send AppendEntries RPCs to all peers
        log.trace("Node {} sending heartbeats, term={}", nodeId, currentTerm.get());
    }

    public void onReceiveHeartbeat(String fromLeader, long term) {
        if (term >= currentTerm.get()) {
            currentTerm.set(term);
            state = RaftState.FOLLOWER;
            leaderId = fromLeader;
            votedFor = null;
            resetElectionTimer();
        }
    }

    private void resetElectionTimer() {
        if (electionTimer != null) electionTimer.cancel(false);
        long timeout = electionTimeoutMs + random.nextInt(200);
        electionTimer = scheduler.schedule(this::startElection, timeout, TimeUnit.MILLISECONDS);
    }

    public void addLeaderChangeListener(LeaderChangeListener listener) {
        leaderChangeListeners.add(listener);
    }

    public String nodeId() { return nodeId; }
    public RaftState state() { return state; }
    public long currentTerm() { return currentTerm.get(); }
    public String leaderId() { return leaderId; }
    public boolean isLeader() { return state == RaftState.LEADER; }

    @FunctionalInterface
    public interface LeaderChangeListener {
        void onLeaderChange(String newLeaderId, long term);
    }
}
