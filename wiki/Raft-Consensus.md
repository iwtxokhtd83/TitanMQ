# Raft Consensus

TitanMQ embeds a full Raft consensus implementation for cluster coordination. No ZooKeeper, no etcd — zero external dependencies.

## Why Embedded Raft?

| Approach | Used By | Drawback |
|---|---|---|
| ZooKeeper | Kafka (legacy) | Separate service to deploy, monitor, and maintain |
| KRaft | Kafka (new) | Still complex; took years to stabilize |
| Embedded Raft | TitanMQ | Zero dependencies; sub-second failover |

## What Raft Manages

- Leader election among broker nodes
- Metadata replication (topic/partition assignments)
- Partition leadership assignment
- Cluster membership changes

## Leader Election

When a broker starts, it begins as a **FOLLOWER**. If it doesn't hear from a leader within the election timeout (300–500ms, randomized), it transitions to **CANDIDATE** and requests votes:

```
FOLLOWER ──(timeout)──→ CANDIDATE ──(majority votes)──→ LEADER
    ↑                       │                              │
    │                       │(higher term discovered)      │
    └───────────────────────┘                              │
    ↑                                                      │
    └──────────(higher term discovered)────────────────────┘
```

The election uses the **log up-to-date check** (§5.4.1): a candidate's log must be at least as up-to-date as the voter's log. This ensures the new leader has all committed entries.

## Log Replication

The leader replicates entries to followers via `AppendEntries` RPCs:

1. Leader appends entry to its local log
2. Leader sends `AppendEntries` to all followers
3. Followers verify log consistency and append
4. When a majority acknowledges, the entry is **committed**
5. Leader notifies followers of the new commit index

### No-Op on Election

When a new leader is elected, it immediately appends a **no-op entry** in its own term. This is critical for liveness: Raft only commits entries from the current term (§5.4.2). Without the no-op, a new leader with only previous-term entries could never advance the commit index.

## Log Divergence Resolution

The classic problem: a leader crashes after appending to its local log but before replicating to a majority.

```
Before crash:
  Leader:   [a@T1, b@T1, c@T1, d@T1]  ← d was never replicated
  Follower: [a@T1, b@T1, c@T1]

After new leader elected at T2:
  New Leader: [a@T1, b@T1, c@T1, e@T2]  ← e is the no-op
  Follower:   [a@T1, b@T1, c@T1]         ← fine, just append e

  Old Leader (if it comes back):
              [a@T1, b@T1, c@T1, d@T1]  ← d conflicts with e
              After AppendEntries from new leader:
              [a@T1, b@T1, c@T1, e@T2]  ← d truncated, e replicated
```

The follower's `handleAppendEntries()` detects the conflict (different term at the same index) and truncates the divergent suffix. This is safe because `d` was never committed.

## Optimized Backtracking

When a follower rejects `AppendEntries` (log inconsistency), it returns:
- `conflictTerm`: the term of the conflicting entry
- `conflictIndex`: the first index of that term

This lets the leader skip directly to the divergence point instead of decrementing `nextIndex` by 1 per RPC (O(n) → O(1)).

## Safety Properties

1. **Election Safety**: At most one leader per term
2. **Leader Append-Only**: A leader never overwrites or deletes its own entries
3. **Log Matching**: If two logs contain an entry with the same index and term, all preceding entries are identical
4. **Leader Completeness**: If an entry is committed, it will be present in all future leaders' logs
5. **State Machine Safety**: If a node applies an entry at a given index, no other node applies a different entry at that index

## Configuration

```bash
java -jar titanmq-server.jar \
  --node-id broker-1 \
  --port 9500 \
  --peers broker-2,broker-3
```

Internal timing parameters:
- Election timeout: 300–500ms (randomized to prevent split votes)
- Heartbeat interval: 150ms

## References

- [Raft paper](https://raft.github.io/raft.pdf) — Ongaro & Ousterhout, 2014
- §5.3 — Log backtracking optimization
- §5.4.2 — Committing entries from previous terms
- §6.4 (dissertation) — No-op on election
- Figure 8 — The classic leader crash scenario
