package com.titanmq.store.tier;

/**
 * Storage tier classification for tiered storage.
 *
 * <p>Messages flow through tiers based on age and access patterns:
 * <ul>
 *   <li>HOT: Active segment, memory-mapped, fastest reads/writes</li>
 *   <li>WARM: Recent segments on local SSD, good read performance</li>
 *   <li>COLD: Archived segments in remote/object storage, cheapest but slowest</li>
 * </ul>
 */
public enum StorageTier {
    /** Active data in memory-mapped files. Sub-millisecond access. */
    HOT,
    /** Recent data on local disk. Millisecond access. */
    WARM,
    /** Archived data in remote storage. Higher latency, lowest cost. */
    COLD
}
