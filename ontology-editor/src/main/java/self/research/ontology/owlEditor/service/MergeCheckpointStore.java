package self.research.ontology.owlEditor.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class MergeCheckpointStore {

    static final long PROJECT_CAP_BYTES = 200L * 1024 * 1024;
    private static final String DIRECTORY = "merge-checkpoints";

    private final StorageManager storageManager;

    MergeCheckpointStore(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    void saveBefore(String projectId, String checkpointId, Path source) throws IOException {
        save(projectId, checkpointId, "before", source);
    }

    void saveAfter(String projectId, String checkpointId, Path source) throws IOException {
        save(projectId, checkpointId, "after", source);
    }

    boolean exists(String projectId, String checkpointId, boolean undo) {
        return Files.isRegularFile(fileOf(projectId, checkpointId, undo ? "before" : "after"));
    }

    void restoreTo(String projectId, String checkpointId, boolean undo, Path target) throws IOException {
        Path source = fileOf(projectId, checkpointId, undo ? "before" : "after");
        Path temp = target.resolveSibling(target.getFileName() + ".restore");
        try (InputStream in = new GZIPInputStream(Files.newInputStream(source))) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void save(String projectId, String checkpointId, String side, Path source) throws IOException {
        Path file = fileOf(projectId, checkpointId, side);
        Files.createDirectories(file.getParent());
        try (InputStream in = Files.newInputStream(source);
             OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            in.transferTo(out);
        }
        enforceCap(projectId, checkpointId);
    }

    private void enforceCap(String projectId, String keepCheckpointId) throws IOException {
        Path dir = directoryOf(projectId);
        List<Path> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files = listing.sorted(Comparator.comparingLong(MergeCheckpointStore::modified)).toList();
        }
        long total = 0;
        for (Path file : files) {
            total += Files.size(file);
        }
        for (Path file : files) {
            if (total <= PROJECT_CAP_BYTES) {
                return;
            }
            if (file.getFileName().toString().contains(keepCheckpointId)) {
                continue;
            }
            total -= Files.size(file);
            Files.deleteIfExists(file);
        }
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private Path directoryOf(String projectId) {
        return storageManager.projectDir(projectId).resolve(DIRECTORY);
    }

    private Path fileOf(String projectId, String checkpointId, String side) {
        return directoryOf(projectId).resolve(side + "-" + checkpointId.replaceAll("[^A-Za-z0-9-]", "") + ".owl.gz");
    }
}
