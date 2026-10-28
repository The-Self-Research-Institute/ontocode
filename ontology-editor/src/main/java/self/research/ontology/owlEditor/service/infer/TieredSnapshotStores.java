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

    private final long memoryBelow;
    private final Path root;

    public TieredSnapshotStores() {
        this(MEMORY_BELOW_TRIPLES);
    }

    public TieredSnapshotStores(long memoryBelow) {
        this.memoryBelow = memoryBelow;
        this.root = Path.of(System.getProperty("java.io.tmpdir"), "ontocode-inference-" + UUID.randomUUID());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(root)));
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
