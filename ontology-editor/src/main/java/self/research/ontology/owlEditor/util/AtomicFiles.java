package self.research.ontology.owlEditor.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Slf4j
public final class AtomicFiles {

    private static final String STAGED_SUFFIX = ".staged";

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

    private static void replace(Path staged, Path target) throws IOException {
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | AccessDeniedException e) {
            log.warn("Atomic replace of {} not possible ({}); overwriting in place", target, e.getClass().getSimpleName());
            Files.copy(staged, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
