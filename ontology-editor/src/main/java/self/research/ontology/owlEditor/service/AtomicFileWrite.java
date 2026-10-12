package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class AtomicFileWrite {

    private static final Logger log = LoggerFactory.getLogger(AtomicFileWrite.class);
    static final int MAX_ATTEMPTS = 40;
    private static final long RETRY_MILLIS = 50;

    interface Writer {
        void write(OutputStream out) throws IOException;
    }

    private AtomicFileWrite() {
    }

    static void writeString(Path target, String content) throws IOException {
        write(target, out -> out.write(content.getBytes(StandardCharsets.UTF_8)));
    }

    static void write(Path target, Writer writer) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                writer.write(out);
            }
            replace(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void replace(Path temp, Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("Could not swap in {} after {} attempts ({}); copying over it instead",
                            target.getFileName(), attempt, e.getMessage());
                    Files.copy(temp, target, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
                pause();
            }
        }
    }

    private static void pause() throws IOException {
        try {
            Thread.sleep(RETRY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while replacing an export file", e);
        }
    }
}
