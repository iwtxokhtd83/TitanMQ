package com.titanmq.cluster;

/**
 * Raft consensus node states.
 */
public enum RaftState {
    FOLLOWER,
    CANDIDATE,
    LEADER
}
