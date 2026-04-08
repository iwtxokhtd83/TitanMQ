# TitanMQ Wiki

Welcome to the TitanMQ wiki — the comprehensive guide for the next-generation message queue that unifies the strengths of Kafka, RabbitMQ, and ZeroMQ.

## Navigation

### Getting Started
- [[Quick Start Guide]] — Get up and running in 5 minutes
- [[Installation]] — Build from source and system requirements
- [[Configuration Reference]] — All broker configuration options

### Core Concepts
- [[Architecture Overview]] — System design and module structure
- [[Message Model]] — Message format, headers, and serialization
- [[Topics and Partitions]] — How data is organized and distributed

### Features
- [[Routing Engine]] — Direct, Topic, Fanout, and Content-Based exchanges
- [[Back-Pressure]] — Adaptive flow control with dual watermarks
- [[Tiered Storage]] — HOT → WARM → COLD data lifecycle
- [[Embedded Mode]] — Brokerless, in-process operation (ZeroMQ-style)

### Cluster & Replication
- [[Raft Consensus]] — Leader election, log replication, and safety guarantees
- [[Cluster Deployment]] — Multi-node setup and configuration

### Client SDK
- [[Producer API]] — Sending messages (sync, async, batched)
- [[Consumer API]] — Consuming messages (poll, subscribe, consumer groups)

### Operations
- [[Wire Protocol]] — Binary protocol specification
- [[Benchmarking]] — Running performance tests
- [[Troubleshooting]] — Common issues and solutions

### Development
- [[Contributing]] — How to contribute to TitanMQ
- [[Module Guide]] — Source code organization
- [[Design Decisions]] — Why we made the choices we did
