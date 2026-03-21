package com.titanmq.server;

import com.titanmq.common.config.BrokerConfig;
import com.titanmq.core.BackPressureController;
import com.titanmq.core.ConsumerGroupManager;
import com.titanmq.core.TopicManager;
import com.titanmq.cluster.RaftNode;
import com.titanmq.store.OffsetStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * TitanMQ Broker — the main server process.
 *
 * <p>Orchestrates all subsystems:
 * <ul>
 *   <li>Network layer (Netty-based TCP server)</li>
 *   <li>Topic/partition management</li>
 *   <li>Consumer group coordination</li>
 *   <li>Back-pressure control</li>
 *   <li>Raft-based cluster consensus</li>
 * </ul>
 */
public class TitanBroker {

    private static final Logger log = LoggerFactory.getLogger(TitanBroker.class);

    private final BrokerConfig config;
    private final TopicManager topicManager;
    private final ConsumerGroupManager consumerGroupManager;
    private final BackPressureController backPressureController;
    private final OffsetStore offsetStore;
    private final RaftNode raftNode;
    private BrokerNetworkServer networkServer;

    public TitanBroker(BrokerConfig config) {
        this.config = config;
        this.topicManager = new TopicManager(config);
        this.offsetStore = new OffsetStore();
        this.consumerGroupManager = new ConsumerGroupManager(offsetStore);
        this.backPressureController = new BackPressureController(
                config.backPressureHighWaterMark(),
                config.backPressureLowWaterMark()
        );
        this.raftNode = new RaftNode("broker-1", List.of());
    }

    public void start() throws IOException, InterruptedException {
        log.info("Starting TitanMQ Broker on port {}", config.port());
        log.info("  Data directory: {}", config.dataDir());
        log.info("  Default partitions: {}", config.numPartitions());
        log.info("  Segment size: {} bytes", config.segmentSizeBytes());
        log.info("  Zero-copy: {}", config.enableZeroCopy());

        // Start Raft consensus
        raftNode.start();

        // Start network server
        networkServer = new BrokerNetworkServer(config, topicManager, consumerGroupManager, backPressureController);
        networkServer.start();

        log.info("TitanMQ Broker started successfully");
    }

    public void shutdown() throws IOException {
        log.info("Shutting down TitanMQ Broker...");
        if (networkServer != null) networkServer.stop();
        raftNode.stop();
        topicManager.close();
        log.info("TitanMQ Broker stopped");
    }

    public TopicManager topicManager() { return topicManager; }
    public ConsumerGroupManager consumerGroupManager() { return consumerGroupManager; }
    public BackPressureController backPressureController() { return backPressureController; }

    public static void main(String[] args) throws Exception {
        BrokerConfig config = new BrokerConfig();

        // Parse CLI args
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> config.port(Integer.parseInt(args[++i]));
                case "--data-dir" -> config.dataDir(java.nio.file.Path.of(args[++i]));
                case "--partitions" -> config.numPartitions(Integer.parseInt(args[++i]));
            }
        }

        TitanBroker broker = new TitanBroker(config);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { broker.shutdown(); } catch (IOException e) { log.error("Error during shutdown", e); }
        }));
        broker.start();
    }
}
