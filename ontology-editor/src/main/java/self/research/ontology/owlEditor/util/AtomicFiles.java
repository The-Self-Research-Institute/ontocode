package self.research.ontology.owlEditor.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
public final class AtomicFiles {

    private static final String STAGED_SUFFIX = ".staged";
    private static final String ASIDE_SUFFIX = ".old" + STAGED_SUFFIX;
    private static final Pattern ASIDE = Pattern.compile("(.+)\\.[0-9a-f-]{36}\\.old\\.staged");
    private static final int RECOVERY_DEPTH = 6;
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

    static Path stepAside(Path target) throws IOException {
        Path aside = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ASIDE_SUFFIX);
        Files.move(target, aside, StandardCopyOption.ATOMIC_MOVE);
        return aside;
    }

    public static int recoverAll(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return 0;
        }
        Set<Path> targets = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(root, RECOVERY_DEPTH)) {
            files.forEach(file -> {
                Matcher match = ASIDE.matcher(file.getFileName().toString());
                if (match.matches()) {
                    targets.add(file.resolveSibling(match.group(1)));
                }
            });
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not look for files left by interrupted saves under {}: {}", root, e.getMessage());
        }
        int restored = 0;
        for (Path target : targets) {
            try {
                restored += recover(target) ? 1 : 0;
            } catch (IOException e) {
                log.warn("Could not recover {} after an interrupted save: {}", target, e.getMessage());
            }
        }
        return restored;
    }

    static boolean recover(Path target) throws IOException {
        String name = target.getFileName().toString();
        List<Path> asides;
        try (Stream<Path> files = Files.list(target.getParent())) {
            asides = files.filter(file -> {
                Matcher match = ASIDE.matcher(file.getFileName().toString());
                return match.matches() && match.group(1).equals(name);
            }).sorted(Comparator.comparing(AtomicFiles::modified).reversed()).toList();
        }
        boolean restored = !asides.isEmpty() && !Files.exists(target);
        if (restored) {
            Files.move(asides.get(0), target, StandardCopyOption.ATOMIC_MOVE);
            log.warn("Restored {} from the copy kept by a save that was interrupted", target);
        }
        for (Path leftover : asides.subList(restored ? 1 : 0, asides.size())) {
            Files.deleteIfExists(leftover);
        }
        return restored;
    }

    private static FileTime modified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    private static boolean swapAside(Path staged, Path target) throws IOException {
        Path aside;
        try {
            aside = stepAside(target);
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
