package com.titanmq.store;

import com.titanmq.common.TitanMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommitLogTest {

    private Path tempDir;
    private CommitLog commitLog;

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("titanmq-test");
        commitLog = new CommitLog(tempDir, 1024 * 1024); // 1MB segments
    }

    @AfterEach
    void teardown() throws IOException {
        commitLog.close();
        Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete());
    }

    @Test
    void shouldAppendAndReadSingleMessage() throws IOException {
        TitanMessage msg = TitanMessage.builder()
                .topic("test")
                .key("key-1")
                .payload("hello")
                .build();

        long offset = commitLog.append(msg);
        assertEquals(0, offset);

        List<TitanMessage> messages = commitLog.read(0, 10);
        assertEquals(1, messages.size());
        assertArrayEquals("hello".getBytes(), messages.get(0).payload());
    }

    @Test
    void shouldAppendMultipleMessages() throws IOException {
        for (int i = 0; i < 100; i++) {
            TitanMessage msg = TitanMessage.builder()
                    .topic("test")
                    .key("key-" + i)
                    .payload("message-" + i)
                    .build();
            long offset = commitLog.append(msg);
            assertEquals(i, offset);
        }

        assertEquals(100, commitLog.latestOffset());

        List<TitanMessage> messages = commitLog.read(0, 100);
        assertEquals(100, messages.size());
    }

    @Test
    void shouldReadFromSpecificOffset() throws IOException {
        for (int i = 0; i < 50; i++) {
            commitLog.append(TitanMessage.builder()
                    .topic("test")
                    .key("key-" + i)
                    .payload("msg-" + i)
                    .build());
        }

        List<TitanMessage> messages = commitLog.read(25, 10);
        assertEquals(10, messages.size());
        assertArrayEquals("msg-25".getBytes(), messages.get(0).payload());
    }

    @Test
    void shouldRollSegmentWhenFull() throws IOException {
        // Use tiny segment size to force rolling
        commitLog.close();
        commitLog = new CommitLog(tempDir.resolve("roll-test"), 512);

        for (int i = 0; i < 20; i++) {
            commitLog.append(TitanMessage.builder()
                    .topic("test")
                    .key("k")
                    .payload("payload-" + i)
                    .build());
        }

        // Should still be able to read all messages across segments
        List<TitanMessage> messages = commitLog.read(0, 20);
        assertEquals(20, messages.size());
    }

    @Test
    void shouldReturnEmptyListForOffsetBeyondEnd() throws IOException {
        commitLog.append(TitanMessage.builder()
                .topic("test")
                .payload("data")
                .build());

        List<TitanMessage> messages = commitLog.read(100, 10);
        assertTrue(messages.isEmpty());
    }
}
