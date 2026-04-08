package com.titanmq.store;

/**
 * Controls when data is fsynced to disk for durability guarantees.
 */
public enum FlushPolicy {
    /** fsync after every message. Safest, slowest. */
    EVERY_MESSAGE,
    /** fsync periodically based on flushIntervalMs. Good balance. */
    PERIODIC,
    /** Never explicitly fsync — rely on OS page cache. Fastest, least safe. */
    NONE
}
