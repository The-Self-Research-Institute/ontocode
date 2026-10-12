package self.research.ontology.owlEditor.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
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

    public static void copy(Path source, Path target) throws IOException {
        Path staged = Files.createTempFile(target.getParent(), "content-", STAGED_SUFFIX);
        try {
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
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
