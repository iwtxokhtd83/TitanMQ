package com.titanmq.store.tier;

import com.titanmq.common.TitanMessage;
import com.titanmq.store.LogSegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TieredStorageTest {

    private Path tempDir;
    private Path coldDir;

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("titanmq-tier-test");
        coldDir = Files.createTempDirectory("titanmq-cold-test");
    }

    @AfterEach
    void teardown() throws IOException {
        for (Path dir : List.of(tempDir, coldDir)) {
            if (Files.exists(dir)) {
                Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void shouldStartInHotTier() throws IOException {
        LogSegment segment = createSegmentWithMessages(5);
        RemoteStorageBackend backend = new LocalFileStorageBackend(coldDir);
        Path segmentPath = tempDir.resolve(String.format("%020d.log", 0L));

        TieredSegment tiered = new TieredSegment(segment, segmentPath, backend);
        assertEquals(StorageTier.HOT, tiered.currentTier());
    }

    @Test
    void shouldDemoteToWarm() throws IOException {
        LogSegment segment = createSegmentWithMessages(5);
        RemoteStorageBackend backend = new LocalFileStorageBackend(coldDir);
        Path segmentPath = tempDir.resolve(String.format("%020d.log", 0L));

        TieredSegment tiered = new TieredSegment(segment, segmentPath, backend);
        tiered.demoteToWarm();
        assertEquals(StorageTier.WARM, tiered.currentTier());
    }

    @Test
    void shouldOffloadToCold() throws IOException {
        LogSegment segment = createSegmentWithMessages(5);
        RemoteStorageBackend backend = new LocalFileStorageBackend(coldDir);
        Path segmentPath = tempDir.resolve(String.format("%020d.log", 0L));

        TieredSegment tiered = new TieredSegment(segment, segmentPath, backend);
        tiered.offloadToCold();
        assertEquals(StorageTier.COLD, tiered.currentTier());

        // Verify file was uploaded to cold storage
        assertTrue(backend.exists("segments/" + segmentPath.getFileName().toString()));
    }

    @Test
    void shouldReadFromColdTierTransparently() throws IOException {
        LogSegment segment = createSegmentWithMessages(5);
        RemoteStorageBackend backend = new LocalFileStorageBackend(coldDir);
        Path segmentPath = tempDir.resolve(String.format("%020d.log", 0L));

        TieredSegment tiered = new TieredSegment(segment, segmentPath, backend);

        // Read while HOT
        List<TitanMessage> hotMessages = tiered.read(0, 5);
        assertEquals(5, hotMessages.size());

        // Offload to cold
        tiered.offloadToCold();
        assertEquals(StorageTier.COLD, tiered.currentTier());

        // Read from cold — should transparently fetch
        List<TitanMessage> coldMessages = tiered.read(0, 5);
        assertEquals(5, coldMessages.size());
        assertArrayEquals(hotMessages.get(0).payload(), coldMessages.get(0).payload());
    }

    @Test
    void tieredStorageManagerShouldTrackStats() throws IOException {
        RemoteStorageBackend backend = new LocalFileStorageBackend(coldDir);
        TierConfig config = new TierConfig()
                .hotRetention(Duration.ofMillis(1))
                .warmRetention(Duration.ofMillis(1));

        TieredStorageManager manager = new TieredStorageManager(config, backend);

        LogSegment seg1 = createSegmentWithMessages(3);
        LogSegment seg2 = createSegmentWithMessages(3);
        Path path1 = tempDir.resolve(String.format("%020d.log", 0L));
        Path path2 = tempDir.resolve(String.format("%020d.log", 3L));

        TieredSegment ts1 = new TieredSegment(seg1, path1, backend);
        TieredSegment ts2 = new TieredSegment(seg2, path2, backend);

        manager.addSegment(ts1);
        manager.addSegment(ts2);

        var stats = manager.getStats();
        assertEquals(2, stats.hotSegments());
        assertEquals(0, stats.warmSegments());
        assertEquals(2, stats.totalSegments());

        ts1.demoteToWarm();
        stats = manager.getStats();
        assertEquals(1, stats.hotSegments());
        assertEquals(1, stats.warmSegments());
    }

    @Test
    void localFileStorageBackendShouldRoundTrip() throws IOException {
        RemoteStorageBackend backend = new LocalFileStorageBackend(coldDir);

        // Create a test file
        Path testFile = tempDir.resolve("test.dat");
        Files.writeString(testFile, "hello tiered storage");

        // Upload
        backend.upload(testFile, "test/test.dat");
        assertTrue(backend.exists("test/test.dat"));

        // Download
        Path downloaded = tempDir.resolve("downloaded.dat");
        backend.download("test/test.dat", downloaded);
        assertEquals("hello tiered storage", Files.readString(downloaded));

        // Delete
        backend.delete("test/test.dat");
        assertFalse(backend.exists("test/test.dat"));
    }

    private LogSegment createSegmentWithMessages(int count) throws IOException {
        LogSegment segment = new LogSegment(tempDir, 0, 1024 * 1024);
        for (int i = 0; i < count; i++) {
            TitanMessage msg = TitanMessage.builder()
                    .topic("test")
                    .key("key-" + i)
                    .payload("payload-" + i)
                    .build();
            segment.append(i, msg.serialize());
        }
        return segment;
    }
}
