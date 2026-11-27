package self.research.ontology.owlEditor.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFilesTest {

    @TempDir
    Path dir;

    private Path staged(String content) throws IOException {
        Path staged = Files.createTempFile(dir, "content-", ".staged");
        Files.writeString(staged, content);
        return staged;
    }

    private List<String> names() throws IOException {
        try (var files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void aSaveGoesThroughWhileAReaderHasTheFileOpenAndTheReaderKeepsTheOldVersion() throws Exception {
        Path target = dir.resolve("ontology.current.owl");
        Files.writeString(target, "old");

        try (InputStream reader = Files.newInputStream(target)) {
            CompletableFuture.runAsync(() -> {
                try {
                    AtomicFiles.replace(staged("new"), target);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).get(5, TimeUnit.SECONDS);

            assertEquals("new", Files.readString(target));
            assertEquals("old", new String(reader.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(List.of("ontology.current.owl"), names());
    }

    @Test
    void readersNeverSeeAHalfWrittenFileWhileItIsReplacedAgainAndAgain() throws Exception {
        Path target = dir.resolve("ontology.current.owl");
        String a = "A".repeat(200_000);
        String b = "B".repeat(200_000);
        Files.writeString(target, a);
        AtomicBoolean writing = new AtomicBoolean(true);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger partial = new AtomicInteger();

        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            while (writing.get()) {
                try (InputStream in = Files.newInputStream(target)) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    reads.incrementAndGet();
                    if (!content.equals(a) && !content.equals(b)) {
                        partial.incrementAndGet();
                    }
                } catch (IOException ignored) {
                }
            }
        });
        try {
            for (int i = 0; i < 40; i++) {
                AtomicFiles.replace(staged(i % 2 == 0 ? b : a), target);
            }
        } finally {
            writing.set(false);
            reader.get(10, TimeUnit.SECONDS);
        }

        assertTrue(reads.get() > 0);
        assertEquals(0, partial.get());
        assertEquals(a, Files.readString(target));
        assertEquals(List.of("ontology.current.owl"), names());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aProgramThatLocksTheFileMakesTheSaveWaitThenFailWithTheOldFileKept() throws Exception {
        Path target = dir.resolve("ontology.current.owl");
        Files.writeString(target, "old");
        Path staged = staged("new");

        try (FileInputStream lock = new FileInputStream(target.toFile())) {
            long start = System.nanoTime();
            IOException error = assertThrows(IOException.class, () -> AtomicFiles.replace(staged, target, 300));
            long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(error.getMessage().contains("previous version was kept"), error.getMessage());
            assertTrue(tookMillis >= 300, "gave up after " + tookMillis + " ms");
            assertEquals("old", new String(lock.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals("old", Files.readString(target));
        assertEquals("new", Files.readString(staged));
        Files.delete(staged);
        assertEquals(List.of("ontology.current.owl"), names());
    }

    @Test
    void aProgramThatLetsGoInTimeLetsTheSaveFinish() throws Exception {
        Path target = dir.resolve("ontology.current.owl");
        Files.writeString(target, "old");
        FileInputStream lock = new FileInputStream(target.toFile());
        CompletableFuture<Void> release = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(300);
                lock.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        AtomicFiles.replace(staged("new"), target, 5_000);
        release.get(5, TimeUnit.SECONDS);

        assertEquals("new", Files.readString(target));
        assertEquals(List.of("ontology.current.owl"), names());
    }

    @Test
    void aSaveToANewFileJustCreatesIt() throws Exception {
        Path target = dir.resolve("fresh.owl");

        AtomicFiles.replace(staged("first"), target);

        assertEquals("first", Files.readString(target));
        assertEquals(List.of("fresh.owl"), names());
    }

    @Test
    void aSaveStoppedBetweenTheTwoMovesLeavesTheOriginalUsableAfterARestart() throws Exception {
        Path project = Files.createDirectories(dir.resolve("projects").resolve("p1"));
        Path target = project.resolve("ontology.current.owl");
        Files.writeString(target, "old");
        AtomicFiles.stepAside(target);
        assertFalse(Files.exists(target));

        assertEquals(1, AtomicFiles.recoverAll(dir.resolve("projects")));

        assertEquals("old", Files.readString(target));
        try (var files = Files.list(project)) {
            assertEquals(List.of("ontology.current.owl"), files.map(p -> p.getFileName().toString()).toList());
        }
        AtomicFiles.replace(staged("newer"), target);
        assertEquals("newer", Files.readString(target));
    }

    @Test
    void aSaveStoppedAfterTheNewFileWentInKeepsTheNewFileAndDropsTheOldCopy() throws Exception {
        Path target = dir.resolve("ontology.current.owl");
        Files.writeString(target, "old");
        Path aside = AtomicFiles.stepAside(target);
        Files.writeString(target, "new");

        assertEquals(0, AtomicFiles.recoverAll(dir));

        assertEquals("new", Files.readString(target));
        assertFalse(Files.exists(aside));
    }

    @Test
    void theNewestKeptCopyWinsWhenSeveralSavesWereInterrupted() throws Exception {
        Path target = dir.resolve("ontology.current.owl");
        Files.writeString(target, "first");
        Path older = AtomicFiles.stepAside(target);
        Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(1_000_000));
        Files.writeString(target, "second");
        AtomicFiles.stepAside(target);

        assertEquals(1, AtomicFiles.recoverAll(dir));

        assertEquals("second", Files.readString(target));
        assertEquals(List.of("ontology.current.owl"), names());
    }

    @Test
    void recoveryLeavesOrdinaryFilesAloneAndCopesWithAMissingFolder() throws Exception {
        Files.writeString(dir.resolve("ontology.current.owl"), "kept");
        Files.writeString(dir.resolve("notes.old.staged.txt"), "not ours");

        assertEquals(0, AtomicFiles.recoverAll(dir));
        assertEquals(0, AtomicFiles.recoverAll(dir.resolve("missing")));

        assertEquals(List.of("notes.old.staged.txt", "ontology.current.owl"), names());
    }
}
