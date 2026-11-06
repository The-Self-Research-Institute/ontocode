package self.research.ontology.common.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import self.research.ontology.common.db.sql.Ids;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public class DiskBlobStore implements BlobStore {

    private static final Logger LOG = LoggerFactory.getLogger(DiskBlobStore.class);
    public static final String STORAGE = "disk";
    private static final String TEMP_DIR = ".tmp";
    private static final int BUFFER = 256 * 1024;

    private final StoredFiles files;
    private final Path root;
    private final long minFreeBytes;

    public DiskBlobStore(StoredFiles files, Path root, long minFreeBytes) {
        this.files = files;
        this.root = root.toAbsolutePath().normalize();
        this.minFreeBytes = minFreeBytes;
    }

    @Override
    public StoredFile store(String bucket, String filename, String contentType, Map<String, Object> metadata, InputStream content) {
        return storeWithId(Ids.newObjectId(), bucket, filename, contentType, metadata, null, content);
    }

    public StoredFile storeWithId(String id, String bucket, String filename, String contentType, Map<String, Object> metadata,
                                  Instant uploadDate, InputStream content) {
        Path temp = root.resolve(TEMP_DIR).resolve(id + ".part");
        Path target = pathOf(bucket, id);
        try {
            Files.createDirectories(temp.getParent());
            requireFreeSpace();
            MessageDigest digest = newDigest();
            long length;
            try (DigestInputStream in = new DigestInputStream(content, digest)) {
                length = copy(in, temp);
            }
            Files.createDirectories(target.getParent());
            move(temp, target);
            try {
                files.upsert(id, bucket, filename, contentType, metadata, length, HexFormat.of().formatHex(digest.digest()), STORAGE, uploadDate);
            } catch (RuntimeException e) {
                deleteQuietly(target);
                throw e;
            }
        } catch (IOException e) {
            deleteQuietly(temp);
            deleteQuietly(target);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            deleteQuietly(temp);
            throw e;
        }
        return files.info(id).orElseThrow(() -> new IllegalStateException("Stored file vanished: " + id));
    }

    public Path contentPath(String bucket, String id) {
        return pathOf(bucket, id);
    }

    @Override
    public Optional<StoredFile> info(String id) {
        return files.info(id);
    }

    @Override
    public InputStream open(String id) {
        StoredFile file = files.info(id).orElseThrow(() -> new IllegalArgumentException("No stored file " + id));
        try {
            return Files.newInputStream(pathOf(file.bucket(), id));
        } catch (NoSuchFileException e) {
            throw new IllegalStateException("The content of stored file " + id + " is missing from disk", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<StoredFile> find(String bucket, String filename, Map<String, String> metadataEquals) {
        return files.find(bucket, filename, metadataEquals);
    }

    @Override
    public Optional<StoredFile> findLatest(String bucket, String filename, Map<String, String> metadataEquals) {
        return files.findLatest(bucket, filename, metadataEquals);
    }

    @Override
    public List<StoredFile> recent(String bucket, int limit) {
        return files.recent(bucket, limit);
    }

    @Override
    public boolean delete(String id) {
        Optional<StoredFile> file = files.info(id);
        if (file.isEmpty()) {
            return false;
        }
        boolean removed = files.deleteRow(id);
        deleteQuietly(pathOf(file.get().bucket(), id));
        return removed;
    }

    @Override
    public int deleteWhere(String bucket, String filename, Map<String, String> metadataEquals) {
        int removed = 0;
        for (StoredFile file : files.findAllWhere(bucket, filename, metadataEquals)) {
            if (delete(file.id())) {
                removed++;
            }
        }
        return removed;
    }

    public int sweepOrphans(Duration minAge) {
        Instant cutoff = Instant.now().minus(minAge);
        int removed = 0;
        removed += sweepTemporary(cutoff);
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, 4)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(root.resolve(TEMP_DIR)))
                    .filter(p -> olderThan(p, cutoff))
                    .forEach(candidates::add);
        } catch (IOException e) {
            LOG.warn("[Blobs] Could not scan {} for orphan files: {}", root, e.getMessage());
            return removed;
        }
        for (int from = 0; from < candidates.size(); from += 500) {
            List<Path> chunk = candidates.subList(from, Math.min(from + 500, candidates.size()));
            List<String> ids = chunk.stream().map(p -> p.getFileName().toString()).toList();
            Set<String> known = files.existing(ids);
            for (Path path : chunk) {
                if (!known.contains(path.getFileName().toString()) && deleteQuietly(path)) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            LOG.info("[Blobs] Removed {} orphan file(s) from {}", removed, root);
        }
        return removed;
    }

    private int sweepTemporary(Instant cutoff) {
        Path temp = root.resolve(TEMP_DIR);
        int removed = 0;
        if (!Files.isDirectory(temp)) {
            return 0;
        }
        try (Stream<Path> list = Files.list(temp)) {
            for (Path p : (Iterable<Path>) list::iterator) {
                if (olderThan(p, cutoff) && deleteQuietly(p)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            LOG.warn("[Blobs] Could not clean {}: {}", temp, e.getMessage());
        }
        return removed;
    }

    private Path pathOf(String bucket, String id) {
        if (!bucket.matches("[A-Za-z0-9._-]+") || !id.matches("[A-Za-z0-9]+")) {
            throw new IllegalArgumentException("Unsafe bucket or id");
        }
        String a = id.length() >= 2 ? id.substring(0, 2) : "00";
        String b = id.length() >= 4 ? id.substring(2, 4) : "00";
        Path path = root.resolve(bucket).resolve(a).resolve(b).resolve(id).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes the blob folder");
        }
        return path;
    }

    private void requireFreeSpace() throws IOException {
        long usable = Files.getFileStore(root.resolve(TEMP_DIR)).getUsableSpace();
        if (usable < minFreeBytes) {
            throw new InsufficientStorageException("Not enough free disk space to store a file: " + (usable / (1024 * 1024))
                    + " MB free, at least " + (minFreeBytes / (1024 * 1024)) + " MB must stay free");
        }
    }

    private static long copy(InputStream in, Path target) throws IOException {
        long total = 0;
        byte[] buffer = new byte[BUFFER];
        try (var out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                total += read;
            }
        }
        return total;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean olderThan(Path path, Instant cutoff) {
        try {
            FileTime modified = Files.getLastModifiedTime(path);
            return modified.toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean deleteQuietly(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warn("[Blobs] Could not delete {}: {}", path, e.getMessage());
            return false;
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
