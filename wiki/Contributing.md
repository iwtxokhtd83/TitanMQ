# Contributing

We welcome contributions to TitanMQ.

## Development Setup

```bash
git clone https://github.com/iwtxokhtd83/TitanMQ.git
cd TitanMQ
mvn clean install
```

Requirements: Java 21+, Maven 3.9+

## Code Style

- Standard Java conventions
- Use `final` for immutable fields
- Prefer records for value types
- Javadoc for all public APIs
- Keep methods under 30 lines where possible
- Use `var` for local variables when the type is obvious

## Pull Request Process

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Write tests for new functionality
4. Ensure all tests pass: `mvn test`
5. Submit a PR with a clear description

## Commit Messages

Follow conventional commits:

```
feat: add message compaction for changelog topics
fix: resolve log divergence on leader crash
perf: optimize commit log reads with mmap
docs: add tiered storage wiki page
test: add Raft leader election edge cases
```

## Module Guide

When adding a feature, put code in the right module:

| If you're working on... | Module |
|---|---|
| Message format, config, serialization | `titanmq-common` |
| Wire protocol, codecs | `titanmq-protocol` |
| Commit log, segments, storage | `titanmq-store` |
| Topic management, consumer groups, back-pressure | `titanmq-core` |
| Embedded/brokerless mode | `titanmq-core` (embedded package) |
| Exchange routing | `titanmq-routing` |
| Raft consensus, clustering | `titanmq-cluster` |
| Producer/Consumer SDK | `titanmq-client` |
| Broker server, network | `titanmq-server` |
| Performance tests | `titanmq-benchmark` |

## Reporting Issues

Use [GitHub Issues](https://github.com/iwtxokhtd83/TitanMQ/issues) with:
- Clear reproduction steps
- Expected vs. actual behavior
- JDK version and OS
- Relevant log output
