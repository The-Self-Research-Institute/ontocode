package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFileWriteTest {

    @TempDir
    Path dir;

    @Test
    void readersNeverSeeAHalfWrittenFileWhileItIsRewritten() throws Exception {
        Path target = dir.resolve("ontology.original.owl");
        String a = "A".repeat(200_000);
        String b = "B".repeat(200_000);
        AtomicFileWrite.writeString(target, a);
        AtomicBoolean writing = new AtomicBoolean(true);
        AtomicInteger partialReads = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();

        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            while (writing.get()) {
                try (FileInputStream in = new FileInputStream(target.toFile())) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    reads.incrementAndGet();
                    if (!content.equals(a) && !content.equals(b)) {
                        partialReads.incrementAndGet();
                    }
                } catch (Exception ignored) {
                }
            }
        });
        for (int i = 0; i < 40; i++) {
            AtomicFileWrite.writeString(target, i % 2 == 0 ? b : a);
        }
        writing.set(false);
        reader.get();

        assertTrue(reads.get() > 0);
        assertEquals(0, partialReads.get());
        assertEquals(a, Files.readString(target));
    }

    @Test
    void aWriteWaitsForAReaderThatHoldsTheFileOpen() throws Exception {
        Path target = dir.resolve("ontology.original.owl");
        AtomicFileWrite.writeString(target, "old");
        try (FileInputStream in = new FileInputStream(target.toFile())) {
            CompletableFuture<Void> write = CompletableFuture.runAsync(() -> {
                try {
                    AtomicFileWrite.writeString(target, "new");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertEquals("old", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            in.close();
            write.get();
        }
        assertEquals("new", Files.readString(target));
        try (var leftovers = Files.list(dir)) {
            assertEquals(1, leftovers.count());
        }
    }
}
