package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

public final class TieredSnapshotStores implements SnapshotStoreFactory {

    public static final long MEMORY_BELOW_TRIPLES = 100_000L;
    static final String ROOT_PREFIX = "ontocode-inference-";
    private static final String OWNER_FILE = ".owner";
    private static final long ORPHAN_GRACE_MS = 3_600_000L;

    private final long memoryBelow;
    private final Path root;

    public TieredSnapshotStores() {
        this(MEMORY_BELOW_TRIPLES);
    }

    public TieredSnapshotStores(long memoryBelow) {
        this(memoryBelow, Path.of(System.getProperty("java.io.tmpdir")));
    }

    TieredSnapshotStores(long memoryBelow, Path tempBase) {
        this.memoryBelow = memoryBelow;
        sweepOrphans(tempBase);
        this.root = tempBase.resolve(ROOT_PREFIX + UUID.randomUUID());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(root)));
    }

    static int sweepOrphans(Path tempBase) {
        int removed = 0;
        try (Stream<Path> children = Files.list(tempBase)) {
            for (Path dir : (Iterable<Path>) children.filter(p -> p.getFileName().toString().startsWith(ROOT_PREFIX))::iterator) {
                if (Files.isDirectory(dir) && !ownerAlive(dir)) {
                    delete(dir);
                    removed++;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            return removed;
        }
        return removed;
    }

    private static boolean ownerAlive(Path dir) {
        try {
            String[] parts = Files.readString(dir.resolve(OWNER_FILE)).trim().split(":");
            ProcessHandle handle = ProcessHandle.of(Long.parseLong(parts[0])).orElse(null);
            if (handle == null || !handle.isAlive()) {
                return false;
            }
            long started = Long.parseLong(parts[1]);
            return handle.info().startInstant().map(i -> i.toEpochMilli() == started).orElse(true);
        } catch (IOException | RuntimeException e) {
            try {
                return System.currentTimeMillis() - Files.getLastModifiedTime(dir).toMillis() < ORPHAN_GRACE_MS;
            } catch (IOException io) {
                return true;
            }
        }
    }

    private void markOwner() throws IOException {
        Files.createDirectories(root);
        Path marker = root.resolve(OWNER_FILE);
        if (!Files.exists(marker)) {
            ProcessHandle self = ProcessHandle.current();
            long started = self.info().startInstant().map(i -> i.toEpochMilli()).orElse(0L);
            Files.writeString(marker, self.pid() + ":" + started);
        }
    }

    @Override
    public Repository create(long expectedTriples) {
        SailRepository repository;
        if (expectedTriples < memoryBelow) {
            repository = new SailRepository(new MemoryStore());
        } else {
            repository = nativeRepository();
        }
        repository.init();
        return repository;
    }

    private SailRepository nativeRepository() {
        try {
            markOwner();
            Path dir = Files.createDirectories(root.resolve(UUID.randomUUID().toString()));
            return new SailRepository(new NativeStore(dir.toFile(), "spoc,posc,cspo") {
                @Override
                protected void shutDownInternal() throws org.eclipse.rdf4j.sail.SailException {
                    try {
                        super.shutDownInternal();
                    } finally {
                        delete(dir);
                    }
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static boolean isNative(Repository repository) {
        return !(repository instanceof SailRepository s) || s.getSail() instanceof NativeStore;
    }

    private static void delete(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            dir.toFile().deleteOnExit();
        }
    }
}
