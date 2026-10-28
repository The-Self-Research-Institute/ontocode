package self.research.ontology.owlEditor.upload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class UploadStagingSweeperTest {

    @TempDir
    Path data;

    private static void age(Path path, long hours) throws IOException {
        FileTime old = FileTime.fromMillis(System.currentTimeMillis() - hours * 3_600_000L);
        if (Files.isDirectory(path)) {
            try (var walk = Files.walk(path)) {
                for (Path p : walk.toList()) {
                    Files.setLastModifiedTime(p, old);
                }
            }
        } else {
            Files.setLastModifiedTime(path, old);
        }
    }

    @SuppressWarnings("unchecked")
    private UploadStagingStats stats() {
        return new UploadStagingStats(data.toString(), mock(ObjectProvider.class));
    }

    @Test
    void removesOnlyEntriesOlderThanTheLimit() throws IOException {
        Path tmp = Files.createDirectories(data.resolve("tmp"));
        Path oldFile = Files.writeString(tmp.resolve("upload_old.tmp"), "x");
        Path freshFile = Files.writeString(tmp.resolve("upload_fresh.tmp"), "x");
        age(oldFile, 3);
        Path chunks = Files.createDirectories(data.resolve("chunk-uploads/abandoned"));
        Files.writeString(chunks.resolve("0.part"), "abc");
        age(chunks, 5);
        Path liveChunks = Files.createDirectories(data.resolve("chunk-uploads/live"));
        Files.writeString(liveChunks.resolve("0.part"), "abc");

        UploadStagingSweeper sweeper = new UploadStagingSweeper(stats(), new UploadSettings(new MockEnvironment()));
        sweeper.sweepAtStartup();

        assertFalse(Files.exists(oldFile));
        assertTrue(Files.exists(freshFile));
        assertFalse(Files.exists(chunks));
        assertTrue(Files.exists(liveChunks));
    }

    @Test
    void aDirectoryWithAnyRecentFileIsKept() throws IOException {
        Path dir = Files.createDirectories(data.resolve("chunk-uploads/active"));
        Path stale = Files.writeString(dir.resolve("0.part"), "a");
        Files.writeString(dir.resolve("1.part"), "b");
        age(stale, 4);
        assertEquals(0, UploadStagingSweeper.sweep(data.resolve("chunk-uploads"), 2 * 3_600_000L));
        assertTrue(Files.exists(dir));
    }

    @Test
    void periodicSweepRemovesStaleTempFiles() throws IOException {
        Path tmp = Files.createDirectories(data.resolve("tmp"));
        Path oldFile = Files.writeString(tmp.resolve("a.tmp"), "x");
        age(oldFile, 10);
        new UploadStagingSweeper(stats(), new UploadSettings(new MockEnvironment())).sweepPeriodically();
        assertFalse(Files.exists(oldFile));
    }

    @Test
    void missingDirectoriesAreHarmless() {
        assertEquals(0, UploadStagingSweeper.sweep(data.resolve("nope"), 1));
    }

    @Test
    void statsReportDiskUsage() throws IOException {
        Files.writeString(Files.createDirectories(data.resolve("tmp")).resolve("a"), "12345");
        Path chunk = Files.createDirectories(data.resolve("chunk-uploads/u1"));
        Files.writeString(chunk.resolve("0.part"), "1234567890");
        UploadStagingStats.Snapshot s = stats().measure();
        assertEquals(5, s.tmpBytes());
        assertEquals(1, s.tmpEntries());
        assertEquals(10, s.chunkBytes());
        assertEquals(1, s.chunkDirs());
    }
}
