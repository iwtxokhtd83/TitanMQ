# Contributing to TitanMQ

We welcome contributions from the community. Here's how to get started.

## Development Setup

### Prerequisites
- Java 21+
- Maven 3.9+

### Build
```bash
mvn clean install
```

### Run Tests
```bash
mvn test
```

### Run Benchmarks
```bash
mvn -pl titanmq-benchmark exec:java -Dexec.mainClass="com.titanmq.benchmark.ThroughputBenchmark"
```

## Code Style
- Follow standard Java conventions
- Use `final` for immutable fields
- Prefer records for value types
- Write Javadoc for all public APIs
- Keep methods under 30 lines where possible

## Pull Request Process
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Write tests for new functionality
4. Ensure all tests pass (`mvn test`)
5. Submit a pull request with a clear description

## Reporting Issues
- Use GitHub Issues
- Include reproduction steps
- Include JDK version and OS information
