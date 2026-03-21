package com.titanmq.benchmark;

import com.titanmq.common.TitanMessage;
import com.titanmq.store.CommitLog;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for CommitLog write throughput.
 *
 * <p>Measures:
 * <ul>
 *   <li>Single-threaded append throughput (ops/sec)</li>
 *   <li>Average latency per append (ns)</li>
 *   <li>Throughput with varying message sizes</li>
 * </ul>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(1)
public class CommitLogBenchmark {

    private CommitLog commitLog;
    private Path tempDir;

    @Param({"128", "1024", "4096", "16384"})
    private int messageSize;

    private TitanMessage message;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        tempDir = Files.createTempDirectory("titanmq-bench");
        commitLog = new CommitLog(tempDir, 256 * 1024 * 1024); // 256MB segments
        message = TitanMessage.builder()
                .topic("benchmark-topic")
                .key("bench-key")
                .payload(new byte[messageSize])
                .build();
    }

    @Benchmark
    public long appendMessage() throws IOException {
        return commitLog.append(message);
    }

    @TearDown(Level.Trial)
    public void teardown() throws IOException {
        commitLog.close();
        // Clean up temp files
        Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete());
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(CommitLogBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
