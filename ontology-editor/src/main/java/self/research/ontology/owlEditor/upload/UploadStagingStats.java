package self.research.ontology.owlEditor.upload;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

@Component
public class UploadStagingStats {

    public record Snapshot(long tmpBytes, long tmpEntries, long chunkBytes, long chunkDirs, long oldestAgeMs) {
    }

    private static final long CACHE_MS = 30_000;

    private final Path dataDir;
    private final ObjectProvider<MeterRegistry> registry;
    private volatile Snapshot cached;
    private volatile long cachedAt;

    public UploadStagingStats(@Value("${ontocode.data.dir:./data}") String dataDir,
                              ObjectProvider<MeterRegistry> registry) {
        this.dataDir = Path.of(dataDir).toAbsolutePath().normalize();
        this.registry = registry;
    }

    public Path tmpDir() {
        return dataDir.resolve("tmp");
    }

    public Path chunkDir() {
        return dataDir.resolve("chunk-uploads");
    }

    @PostConstruct
    void bind() {
        MeterRegistry meters = registry.getIfAvailable();
        if (meters == null) {
            return;
        }
        Gauge.builder("upload.staging.tmp.bytes", this, s -> s.snapshot().tmpBytes()).register(meters);
        Gauge.builder("upload.staging.chunks.bytes", this, s -> s.snapshot().chunkBytes()).register(meters);
        Gauge.builder("upload.staging.chunks.dirs", this, s -> s.snapshot().chunkDirs()).register(meters);
        Gauge.builder("upload.staging.oldest.age.ms", this, s -> s.snapshot().oldestAgeMs()).register(meters);
    }

    public Snapshot snapshot() {
        long now = System.currentTimeMillis();
        Snapshot s = cached;
        if (s != null && now - cachedAt < CACHE_MS) {
            return s;
        }
        s = measure();
        cached = s;
        cachedAt = now;
        return s;
    }

    public Snapshot measure() {
        long now = System.currentTimeMillis();
        long[] tmp = size(tmpDir(), now);
        long[] chunk = size(chunkDir(), now);
        long chunkDirs = 0;
        if (Files.isDirectory(chunkDir())) {
            try (Stream<Path> list = Files.list(chunkDir())) {
                chunkDirs = list.filter(Files::isDirectory).count();
            } catch (IOException ignored) {
            }
        }
        return new Snapshot(tmp[0], tmp[1], chunk[0], chunkDirs, Math.max(tmp[2], chunk[2]));
    }

    private static long[] size(Path root, long now) {
        long[] out = new long[3];
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    out[0] += Files.size(p);
                    out[1]++;
                    out[2] = Math.max(out[2], now - Files.getLastModifiedTime(p).toMillis());
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        return out;
    }
}
