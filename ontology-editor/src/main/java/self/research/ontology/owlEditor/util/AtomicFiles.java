package self.research.ontology.owlEditor.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Slf4j
public final class AtomicFiles {

    private static final String STAGED_SUFFIX = ".staged";
    static final long MAX_WAIT_MILLIS = 30_000;
    private static final long FIRST_PAUSE_MILLIS = 25;
    private static final long MAX_PAUSE_MILLIS = 1_000;

    private AtomicFiles() {
    }

    public static boolean isStaged(Path file) {
        return file.getFileName().toString().endsWith(STAGED_SUFFIX);
    }

    public static void writeString(Path target, String content) throws IOException {
        Path staged = Files.createTempFile(target.getParent(), "content-", STAGED_SUFFIX);
        try {
            Files.writeString(staged, content, StandardCharsets.UTF_8);
            replace(staged, target);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    public interface ChunkListener {
        void accept(byte[] buffer, int length);
    }

    public static void copy(Path source, Path target) throws IOException {
        copy(source, target, null);
    }

    public static void copy(Path source, Path target, ChunkListener listener) throws IOException {
        Path staged = Files.createTempFile(target.getParent(), "content-", STAGED_SUFFIX);
        try {
            if (listener == null) {
                Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            } else {
                byte[] buffer = new byte[1 << 16];
                try (InputStream in = Files.newInputStream(source); OutputStream out = Files.newOutputStream(staged)) {
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                        listener.accept(buffer, read);
                    }
                }
            }
            replace(staged, target);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    public static void replace(Path staged, Path target) throws IOException {
        replace(staged, target, MAX_WAIT_MILLIS);
    }

    static void replace(Path staged, Path target, long maxWaitMillis) throws IOException {
        long waited = 0;
        long pause = FIRST_PAUSE_MILLIS;
        while (true) {
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                if (swapAside(staged, target)) {
                    return;
                }
                if (waited >= maxWaitMillis) {
                    throw new IOException("Could not replace " + target.getFileName()
                            + " because another program has it open; the previous version was kept", e);
                }
                pause(pause);
                waited += pause;
                pause = Math.min(pause * 2, MAX_PAUSE_MILLIS);
            }
        }
    }

    private static boolean swapAside(Path staged, Path target) throws IOException {
        Path aside = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".old" + STAGED_SUFFIX);
        try {
            Files.move(target, aside, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            return false;
        }
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(aside, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException restore) {
                e.addSuppressed(restore);
                throw new IOException("Could not put " + target.getFileName() + " back; the previous version is "
                        + aside.getFileName(), e);
            }
            return false;
        }
        try {
            Files.deleteIfExists(aside);
        } catch (IOException e) {
            log.warn("Could not remove the replaced copy {}: {}", aside.getFileName(), e.getMessage());
        }
        return true;
    }

    private static void pause(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while replacing a file");
        }
    }
}
