package com.titanmq.common.config;

import java.nio.file.Path;

/**
 * Broker configuration with sensible defaults.
 */
public class BrokerConfig {

    private int port = 9500;
    private Path dataDir = Path.of("data/titanmq");
    private int numPartitions = 8;
    private long segmentSizeBytes = 1024L * 1024 * 1024; // 1GB
    private long flushIntervalMs = 1000;
    private int replicationFactor = 1;
    private int numNetworkThreads = 3;
    private int numIoThreads = 8;
    private long maxMessageSizeBytes = 10 * 1024 * 1024; // 10MB
    private boolean enableZeroCopy = true;
    private int backPressureHighWaterMark = 100_000;
    private int backPressureLowWaterMark = 50_000;
    private String flushPolicy = "PERIODIC"; // EVERY_MESSAGE, PERIODIC, NONE
    private long retentionHours = -1;  // -1 = infinite
    private long retentionBytes = -1;  // -1 = infinite

    public int port() { return port; }
    public BrokerConfig port(int port) { this.port = port; return this; }

    public Path dataDir() { return dataDir; }
    public BrokerConfig dataDir(Path dataDir) { this.dataDir = dataDir; return this; }

    public int numPartitions() { return numPartitions; }
    public BrokerConfig numPartitions(int n) { this.numPartitions = n; return this; }

    public long segmentSizeBytes() { return segmentSizeBytes; }
    public BrokerConfig segmentSizeBytes(long size) { this.segmentSizeBytes = size; return this; }

    public long flushIntervalMs() { return flushIntervalMs; }
    public BrokerConfig flushIntervalMs(long ms) { this.flushIntervalMs = ms; return this; }

    public int replicationFactor() { return replicationFactor; }
    public BrokerConfig replicationFactor(int rf) { this.replicationFactor = rf; return this; }

    public int numNetworkThreads() { return numNetworkThreads; }
    public BrokerConfig numNetworkThreads(int n) { this.numNetworkThreads = n; return this; }

    public int numIoThreads() { return numIoThreads; }
    public BrokerConfig numIoThreads(int n) { this.numIoThreads = n; return this; }

    public long maxMessageSizeBytes() { return maxMessageSizeBytes; }
    public BrokerConfig maxMessageSizeBytes(long size) { this.maxMessageSizeBytes = size; return this; }

    public boolean enableZeroCopy() { return enableZeroCopy; }
    public BrokerConfig enableZeroCopy(boolean enable) { this.enableZeroCopy = enable; return this; }

    public int backPressureHighWaterMark() { return backPressureHighWaterMark; }
    public BrokerConfig backPressureHighWaterMark(int mark) { this.backPressureHighWaterMark = mark; return this; }

    public int backPressureLowWaterMark() { return backPressureLowWaterMark; }
    public BrokerConfig backPressureLowWaterMark(int mark) { this.backPressureLowWaterMark = mark; return this; }

    public String flushPolicy() { return flushPolicy; }
    public BrokerConfig flushPolicy(String policy) { this.flushPolicy = policy; return this; }

    public long retentionHours() { return retentionHours; }
    public BrokerConfig retentionHours(long hours) { this.retentionHours = hours; return this; }

    public long retentionBytes() { return retentionBytes; }
    public BrokerConfig retentionBytes(long bytes) { this.retentionBytes = bytes; return this; }
}
