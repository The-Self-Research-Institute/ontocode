package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import self.research.ontology.owlEditor.util.AtomicFiles;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;

final class CodeViewFileStore {

    private static final Logger log = LoggerFactory.getLogger(CodeViewFileStore.class);

    private final Function<String, Path> projectDirs;
    private final OntologyExporter exporter;

    CodeViewFileStore(Function<String, Path> projectDirs, OntologyExporter exporter) {
        this.projectDirs = projectDirs;
        this.exporter = exporter;
    }

    public void storeCodeViewCache(String projectId, String content, String format) throws IOException {
        Path cacheFile = getCodeViewCachePath(projectId, format);
        Files.createDirectories(cacheFile.getParent());
        AtomicFiles.writeString(cacheFile, content);
        log.info("Stored code view cache for project {} in format {}: {} bytes", 
                 projectId, format, content.length());
    }

    public void storeCodeViewCacheFile(String projectId, String format, Path source) throws IOException {
        Path cacheFile = getCodeViewCachePath(projectId, format);
        Files.createDirectories(cacheFile.getParent());
        AtomicFiles.copy(source, cacheFile);
    }

    public Optional<String> getCodeViewCache(String projectId, String format) {
        Path cacheFile = getCodeViewCachePath(projectId, format);
        if (Files.exists(cacheFile)) {
            try {
                String content = Files.readString(cacheFile, StandardCharsets.UTF_8);
                log.info("Retrieved code view cache for project {} in format {}: {} bytes", 
                         projectId, format, content.length());
                return Optional.of(content);
            } catch (IOException e) {
                log.error("Failed to read code view cache for project {}", projectId, e);
                return Optional.empty();
            }
        }
        log.debug("No code view cache found for project {} in format {}", projectId, format);
        return Optional.empty();
    }

    public void clearCodeViewCache(String projectId) {
        Path cacheDir = projectDirs.apply(projectId).resolve("codeview-cache");
        if (Files.exists(cacheDir)) {
            try (Stream<Path> files = Files.list(cacheDir)) {
                files.filter(file -> !AtomicFiles.isStaged(file)).forEach(file -> {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        log.warn("Failed to delete cache file: {}", file, e);
                    }
                });
                log.info("Cleared code view cache for project {}", projectId);
            } catch (IOException e) {
                log.error("Failed to clear code view cache for project {}", projectId, e);
            }
        }
        bumpPublicGraphVersion(projectId);
    }

    private final ConcurrentHashMap<String, Long> publicGraphVersions = new ConcurrentHashMap<>();
    private final AtomicLong graphVersionCounter = new AtomicLong();
    private volatile PublicGraphVersionStore publicGraphVersionStore;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPublicGraphVersionStore(PublicGraphVersionStore publicGraphVersionStore) {
        this.publicGraphVersionStore = publicGraphVersionStore;
    }

    private void bumpPublicGraphVersion(String projectId) {
        PublicGraphVersionStore store = publicGraphVersionStore;
        if (store == null) {
            publicGraphVersions.merge(projectId, graphVersionCounter.incrementAndGet(), Math::max);
            return;
        }
        try {
            publicGraphVersions.merge(projectId, store.increment(projectId), Math::max);
        } catch (RuntimeException e) {
            log.error("Could not persist public graph version bump for project {}; advancing it in memory only: {}",
                    projectId, e.getMessage());
            publicGraphVersions.merge(projectId, 1L, Long::sum);
        }
    }

    public long getPublicGraphVersion(String projectId) {
        Long cached = publicGraphVersions.get(projectId);
        if (cached != null) {
            return cached;
        }
        PublicGraphVersionStore store = publicGraphVersionStore;
        if (store == null) {
            return 0L;
        }
        try {
            return publicGraphVersions.merge(projectId, store.read(projectId), Math::max);
        } catch (RuntimeException e) {
            log.warn("Could not read persisted public graph version for project {}: {}", projectId, e.getMessage());
            return 0L;
        }
    }

    public void clearCodeViewCacheFormat(String projectId, String format) {
        Path cacheFile = getCodeViewCachePath(projectId, format);
        try {
            if (Files.deleteIfExists(cacheFile)) {
                log.info("Cleared code view cache for project {} format {}", projectId, format);
            }
        } catch (IOException e) {
            log.warn("Failed to delete cache file for project {} format {}", projectId, format, e);
        }
    }

    private Path getCodeViewCachePath(String projectId, String format) {
        String extension = OntologyExporter.extensionFor(format);
        return projectDirs.apply(projectId).resolve("codeview-cache").resolve("content." + extension);
    }

    private final ConcurrentHashMap<String, long[]> codeViewLineCounts = new ConcurrentHashMap<>();

    public Path ensureCodeViewFile(String projectId, String format) throws IOException {
        Path cacheFile = getCodeViewCachePath(projectId, format);
        if (Files.exists(cacheFile)) {
            return cacheFile;
        }
        long started = System.nanoTime();
        Path exportPath = exporter.exportOntologyForJob(projectId, format);
        long exported = System.nanoTime();
        Files.createDirectories(cacheFile.getParent());
        AtomicFiles.copy(exportPath, cacheFile);
        log.info("[PERF] Generated code view cache file for project {} format {}: {} bytes, export={}ms copy={}ms",
                projectId, format, Files.size(cacheFile), (exported - started) / 1_000_000,
                (System.nanoTime() - exported) / 1_000_000);
        return cacheFile;
    }

    public StorageManager.CodeViewPage readCodeViewPage(String projectId, String format, long startLine, int lineCount)
            throws IOException {
        long started = System.nanoTime();
        Path file = ensureCodeViewFile(projectId, format);
        long totalBytes = Files.size(file);
        long lastModified = Files.getLastModifiedTime(file).toMillis();
        String countKey = file.toString();
        CodeViewLineIndex index = CodeViewLineIndex.forFile(file, lastModified, totalBytes);
        if (index.usable()) {
            StorageManager.CodeViewPage indexed = index.readPage(file, startLine, lineCount);
            logSlowPageRead(projectId, format, startLine, startLine % CodeViewLineIndex.STRIDE + lineCount, started);
            return indexed;
        }
        long[] cachedCount = codeViewLineCounts.get(countKey);
        long knownTotalLines = (cachedCount != null && cachedCount[0] == lastModified) ? cachedCount[1] : -1;

        StringBuilder page = new StringBuilder();
        long line = 0;
        int collected = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String current;
            while ((current = reader.readLine()) != null) {
                if (line >= startLine && collected < lineCount) {
                    if (collected > 0) {
                        page.append('\n');
                    }
                    page.append(current);
                    collected++;
                    if (collected == lineCount && knownTotalLines >= 0) {
                        logSlowPageRead(projectId, format, startLine, line + 1, started);
                        return new StorageManager.CodeViewPage(page.toString(), startLine, collected, knownTotalLines, totalBytes);
                    }
                }
                line++;
            }
        }
        codeViewLineCounts.put(countKey, new long[] { lastModified, line });
        logSlowPageRead(projectId, format, startLine, line, started);
        return new StorageManager.CodeViewPage(page.toString(), startLine, collected, line, totalBytes);
    }

    private static final long SLOW_PAGE_READ_MS = 50;

    private void logSlowPageRead(String projectId, String format, long startLine, long linesScanned, long startedNanos) {
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;
        if (elapsedMs >= SLOW_PAGE_READ_MS) {
            log.info("[PERF] Slow code view page read project={} format={} startLine={} linesScanned={} duration={}ms",
                    projectId, format, startLine, linesScanned, elapsedMs);
        }
    }
}
