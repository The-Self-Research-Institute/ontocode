package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import self.research.ontology.owlEditor.util.RdfXmlSubjectBlockReader;
import self.research.ontology.owlEditor.util.SubjectRangeIndex;
import self.research.ontology.owlEditor.util.TurtleSubjectBlockReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
final class CodeViewSubjectIndex {

    private static final int MAX_CACHED_FILES = 512;
    private static final ConcurrentHashMap<String, Cached> CACHE = new ConcurrentHashMap<>();

    private record Cached(long lastModified, long size, SubjectRangeIndex index) {}

    private CodeViewSubjectIndex() {
    }

    static boolean supports(String format) {
        return isTurtleFamily(format) || isRdfXml(format);
    }

    static boolean isTurtleFamily(String format) {
        String f = format.toLowerCase(Locale.ROOT);
        return f.equals("turtle") || f.equals("ttl") || f.equals("ntriples") || f.equals("nt");
    }

    static boolean isRdfXml(String format) {
        String f = format.toLowerCase(Locale.ROOT);
        return f.equals("rdfxml") || f.equals("xml") || f.equals("owl");
    }

    static Optional<SubjectRangeIndex> forFile(Path file, String format) throws IOException {
        if (!supports(format)) {
            return Optional.empty();
        }
        long lastModified = Files.getLastModifiedTime(file).toMillis();
        long size = Files.size(file);
        String key = file.toString();
        Cached cached = CACHE.get(key);
        if (cached != null && cached.lastModified() == lastModified && cached.size() == size) {
            return Optional.of(cached.index());
        }
        long started = System.nanoTime();
        SubjectRangeIndex index = build(file, format);
        if (CACHE.size() >= MAX_CACHED_FILES) {
            CACHE.clear();
        }
        CACHE.put(key, new Cached(lastModified, size, index));
        log.info("[PERF] Indexed subjects of {}: {} blocks, complete={} in {}ms", file.getFileName(),
                index.blocks().size(), index.complete(), (System.nanoTime() - started) / 1_000_000);
        return Optional.of(index);
    }

    static SubjectRangeIndex build(Path file, String format) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return isTurtleFamily(format) ? TurtleSubjectBlockReader.index(reader) : RdfXmlSubjectBlockReader.index(reader);
        }
    }
}
