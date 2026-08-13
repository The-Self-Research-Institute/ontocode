package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.ManchesterSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.formats.OBODocumentFormat;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

@Service
public class StorageManager {

    private static final Logger log = LoggerFactory.getLogger(StorageManager.class);

    private final SparqlDatasetService datasetService;
    private final Path projectsRoot;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StorageManager(@Value("${ontocode.data.dir:./data}") String rootDir,
                          SparqlDatasetService datasetService) throws IOException {
        this.datasetService = datasetService;
        this.projectsRoot = Path.of(rootDir).toAbsolutePath().normalize().resolve("projects");
        Files.createDirectories(this.projectsRoot);
    }

    public Path prepareProjectDir(String projectId) throws IOException {
        Path dir = projectDir(projectId);
        Files.createDirectories(dir);
        return dir;
    }

    public Path projectDir(String projectId) {
        return projectsRoot.resolve(projectId);
    }

    public Path resolveProjectFile(String projectId, String filename) {
        return projectDir(projectId).resolve(filename);
    }

    public Path chunkUploadDir(String uploadId) throws IOException {
        Path dir = projectsRoot.getParent().resolve("chunk-uploads").resolve(uploadId);
        Files.createDirectories(dir);
        return dir;
    }

    public Path chunkUploadsRoot() throws IOException {
        Path dir = projectsRoot.getParent().resolve("chunk-uploads");
        Files.createDirectories(dir);
        return dir;
    }

    public Path exportOntology(String projectId, String format) throws IOException {

        log.info("Exporting ontology from GraphDB for project: {}", projectId);
        if (requiresOwlApiFormat(format)) {
            return exportOntologyWithOwlApi(projectId, format);
        }

        RDFFormat rdfFormat = resolveLang(format);
        String extension = extensionFor(format);
        Path exportPath = projectDir(projectId).resolve("ontology.original." + extension);
        Files.createDirectories(exportPath.getParent());
        String content = datasetService.exportDataset(projectId, rdfFormat);

        Map<String, String> citationMappings = getCitationEntityMappings(projectId);
        if (!citationMappings.isEmpty()) {
            log.info("Applying smart citation repositioning for {} citations", citationMappings.size());
            content = repositionCitations(content, citationMappings, format);
        }

        Files.writeString(exportPath, content);
        log.info("Exported ontology to: {}", exportPath);
        return exportPath;
    }

    public Path exportOntologyForJob(String projectId, String format) throws IOException {
        if (requiresOwlApiFormat(format)) {
            return exportOntologyWithOwlApi(projectId, format);
        }
        RDFFormat rdfFormat = resolveLang(format);
        boolean needsBufferedPath = rdfFormat == org.eclipse.rdf4j.rio.RDFFormat.RDFXML
                || !getCitationEntityMappings(projectId).isEmpty();
        if (needsBufferedPath) {
            return exportOntology(projectId, format);
        }

        String extension = extensionFor(format);
        Path exportPath = projectDir(projectId).resolve("ontology.original." + extension);
        Files.createDirectories(exportPath.getParent());
        try (OutputStream out = Files.newOutputStream(exportPath)) {
            datasetService.exportDatasetToStream(projectId, rdfFormat, out);
        }
        log.info("Exported ontology (streamed) to: {} ({} bytes)", exportPath, Files.size(exportPath));
        return exportPath;
    }

    private RDFFormat resolveLang(String format) {
        if (format == null) {
            return org.eclipse.rdf4j.rio.RDFFormat.RDFXML;
        }
        return switch (format.toLowerCase()) {
            case "ttl", "turtle" -> org.eclipse.rdf4j.rio.RDFFormat.TURTLE;
            case "nt", "ntriples" -> org.eclipse.rdf4j.rio.RDFFormat.NTRIPLES;
            case "jsonld" -> org.eclipse.rdf4j.rio.RDFFormat.JSONLD;
            case "rdfxml", "owl", "xml" -> org.eclipse.rdf4j.rio.RDFFormat.RDFXML;
            default -> org.eclipse.rdf4j.rio.RDFFormat.RDFXML;
        };
    }

    public String extensionFor(String format) {
        if (format == null) {
            return "owl";
        }
        return switch (format.toLowerCase()) {
            case "ttl", "turtle" -> "ttl";
            case "nt", "ntriples" -> "nt";
            case "jsonld" -> "jsonld";
            case "rdfxml", "xml" -> "owl";
            case "owlxml" -> "owlxml";
            case "manchester", "manchestersyntax" -> "omn";
            case "functional", "functionalsyntax" -> "ofn";
            case "obo" -> "obo";
            default -> format.toLowerCase();
        };
    }

    private boolean requiresOwlApiFormat(String format) {
        if (format == null) {
            return false;
        }
        String normalized = format.toLowerCase();
        return normalized.equals("owlxml")
                || normalized.equals("manchester")
                || normalized.equals("manchestersyntax")
                || normalized.equals("functional")
                || normalized.equals("functionalsyntax")
                || normalized.equals("obo");
    }

    private Path exportOntologyWithOwlApi(String projectId, String format) throws IOException {
        String extension = extensionFor(format);
        Path exportPath = projectDir(projectId).resolve("ontology.original." + extension);
        Files.createDirectories(exportPath.getParent());

        String rdfXmlContent = datasetService.exportDataset(projectId, RDFFormat.RDFXML);
        if (rdfXmlContent == null || rdfXmlContent.isBlank()) {
            throw new IOException("No RDF/XML content exported from GraphDB for project: " + projectId);
        }
        log.info("Exported {} bytes of RDF/XML from GraphDB for OWL API conversion to {}", rdfXmlContent.length(), format);

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();

        OWLOntologyLoaderConfiguration loaderConfig = new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);
        manager.setOntologyLoaderConfiguration(loaderConfig);
        OWLOntology ontology;
        try (ByteArrayInputStream input = new ByteArrayInputStream(rdfXmlContent.getBytes(StandardCharsets.UTF_8))) {
            ontology = manager.loadOntologyFromOntologyDocument(input);
        } catch (OWLOntologyCreationException e) {
            log.error("OWL API failed to parse RDF/XML for project {}: {}", projectId, e.getMessage());
            throw new IOException("Failed to parse ontology for export: " + projectId + " — " + e.getMessage(), e);
        }

        OWLDocumentFormat sourceFormat = manager.getOntologyFormat(ontology);
        OWLDocumentFormat documentFormat = resolveOwlApiFormat(format);

        if (sourceFormat != null && sourceFormat.isPrefixOWLDocumentFormat()
                && documentFormat.isPrefixOWLDocumentFormat()) {
            var sourcePrefixes = sourceFormat.asPrefixOWLDocumentFormat().getPrefixName2PrefixMap();
            var targetPrefixes = documentFormat.asPrefixOWLDocumentFormat();
            sourcePrefixes.forEach(targetPrefixes::setPrefix);
            log.info("Copied {} prefix mappings to {} format", sourcePrefixes.size(), format);
        }

        try (OutputStream outputStream = Files.newOutputStream(exportPath)) {
            manager.saveOntology(ontology, documentFormat, outputStream);
        } catch (OWLOntologyStorageException e) {
            log.error("OWL API failed to save ontology in {} format for project {}: {}", format, projectId, e.getMessage());
            throw new IOException("Failed to export ontology in format: " + format + " — " + e.getMessage(), e);
        }

        log.info("Exported ontology to: {} ({} bytes)", exportPath, Files.size(exportPath));
        return exportPath;
    }

    private OWLDocumentFormat resolveOwlApiFormat(String format) {
        if (format == null) {
            return new RDFXMLDocumentFormat();
        }
        return switch (format.toLowerCase()) {
            case "turtle", "ttl" -> new TurtleDocumentFormat();
            case "owlxml" -> new OWLXMLDocumentFormat();
            case "manchester", "manchestersyntax" -> new ManchesterSyntaxDocumentFormat();
            case "functional", "functionalsyntax" -> new FunctionalSyntaxDocumentFormat();

            case "obo" -> new OBODocumentFormat();
            case "rdfxml", "rdf", "xml" -> new RDFXMLDocumentFormat();
            default -> new RDFXMLDocumentFormat();
        };
    }

    public List<String> listProjectIds() {
        if (!Files.exists(projectsRoot)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(projectsRoot)) {
            return paths.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list projects", e);
        }
    }

    public Optional<Path> findCurrentOntology(String projectId) {
        Path dir = projectDir(projectId);
        List<String> candidates = List.of(
                "ontology.current.owl",
                "ontology.current.ttl",
                "ontology.current.nt",
                "ontology.current.jsonld"
        );
        for (String candidate : candidates) {
            Path file = dir.resolve(candidate);
            if (Files.exists(file)) {
                return Optional.of(file);
            }
        }
        Path original = dir.resolve("ontology.original.owl");
        return Files.exists(original) ? Optional.of(original) : Optional.empty();
    }

    public Path draftDir(String projectId) {
        return projectDir(projectId).resolve("draft");
    }

    public Path draftOntologyPath(String projectId) {
        return draftDir(projectId).resolve("ontology.draft.owl");
    }

    public boolean hasDraft(String projectId) {
        return Files.exists(draftOntologyPath(projectId));
    }

    public Optional<Path> findWorkingOntology(String projectId) {
        Path draft = draftOntologyPath(projectId);
        if (Files.exists(draft)) {
            return Optional.of(draft);
        }
        return findCurrentOntology(projectId);
    }

    public boolean promoteDraft(String projectId) throws IOException {
        Path draft = draftOntologyPath(projectId);
        if (!Files.exists(draft)) {
            return false;
        }
        Path target = projectDir(projectId).resolve("ontology.current.owl");
        Files.createDirectories(target.getParent());
        try {
            Files.move(draft, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(draft, target, StandardCopyOption.REPLACE_EXISTING);
        }
        deleteDraft(projectId);
        log.info("Draft promoted to saved ontology for project {}", projectId);
        return true;
    }

    public void deleteDraft(String projectId) throws IOException {
        Path dir = draftDir(projectId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Could not delete draft file {}: {}", p, e.getMessage());
                }
            });
        }
    }

    public void storeCodeViewCache(String projectId, String content, String format) throws IOException {
        Path cacheFile = getCodeViewCachePath(projectId, format);
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, content, StandardCharsets.UTF_8);
        log.info("Stored code view cache for project {} in format {}: {} bytes",
                 projectId, format, content.length());
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
        Path cacheDir = projectDir(projectId).resolve("codeview-cache");
        if (Files.exists(cacheDir)) {
            try (Stream<Path> files = Files.list(cacheDir)) {
                files.forEach(file -> {
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

    private void bumpPublicGraphVersion(String projectId) {
        publicGraphVersions.put(projectId, graphVersionCounter.incrementAndGet());
    }

    public long getPublicGraphVersion(String projectId) {
        return publicGraphVersions.getOrDefault(projectId, 0L);
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
        String extension = extensionFor(format);
        return projectDir(projectId).resolve("codeview-cache").resolve("content." + extension);
    }

    public record CodeViewPage(String content, long startLine, int lineCount, long totalLines, long totalBytes) {}

    private final ConcurrentHashMap<String, long[]> codeViewLineCounts = new ConcurrentHashMap<>();

    public Path ensureCodeViewFile(String projectId, String format) throws IOException {
        Path cacheFile = getCodeViewCachePath(projectId, format);
        if (Files.exists(cacheFile)) {
            return cacheFile;
        }
        Path exportPath = exportOntologyForJob(projectId, format);
        Files.createDirectories(cacheFile.getParent());
        Files.copy(exportPath, cacheFile, StandardCopyOption.REPLACE_EXISTING);
        log.info("Generated code view cache file for project {} format {}: {} bytes",
                projectId, format, Files.size(cacheFile));
        return cacheFile;
    }

    public CodeViewPage readCodeViewPage(String projectId, String format, long startLine, int lineCount)
            throws IOException {
        Path file = ensureCodeViewFile(projectId, format);
        long totalBytes = Files.size(file);
        long lastModified = Files.getLastModifiedTime(file).toMillis();
        String countKey = file.toString();
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
                        return new CodeViewPage(page.toString(), startLine, collected, knownTotalLines, totalBytes);
                    }
                }
                line++;
            }
        }
        codeViewLineCounts.put(countKey, new long[] { lastModified, line });
        return new CodeViewPage(page.toString(), startLine, collected, line, totalBytes);
    }

    public void storeCitationEntityMapping(String projectId, String citationUri, String entityUri) throws IOException {
        if (citationUri == null || entityUri == null) {
            log.warn("Cannot store null citation-entity mapping for project {}", projectId);
            return;
        }

        Map<String, String> mappings = getCitationEntityMappings(projectId);
        mappings.put(citationUri, entityUri);

        Path metadataFile = projectDir(projectId).resolve("citation-metadata.json");
        Files.createDirectories(metadataFile.getParent());
        objectMapper.writeValue(metadataFile.toFile(), mappings);

        log.info("Stored citation-entity mapping: {} -> {} for project {}", citationUri, entityUri, projectId);
    }

    public Map<String, String> getCitationEntityMappings(String projectId) {
        Path metadataFile = projectDir(projectId).resolve("citation-metadata.json");
        if (Files.exists(metadataFile)) {
            try {
                return objectMapper.readValue(metadataFile.toFile(),
                    new TypeReference<Map<String, String>>() {});
            } catch (IOException e) {
                log.error("Failed to read citation metadata for project {}", projectId, e);
            }
        }
        return new HashMap<>();
    }

    public void clearCitationEntityMappings(String projectId) {
        Path metadataFile = projectDir(projectId).resolve("citation-metadata.json");
        try {
            Files.deleteIfExists(metadataFile);
            log.info("Cleared citation metadata for project {}", projectId);
        } catch (IOException e) {
            log.warn("Failed to delete citation metadata for project {}", projectId, e);
        }
    }

    public void extractCitationMappingsFromFile(Path filePath, String projectId) {
        try {
            long fileSize = Files.size(filePath);
            String format = detectFormatFromPath(filePath);

            Map<String, String> extractedMappings;
            if (fileSize > 50 * 1024 * 1024) {

                log.info("[PERFORMANCE] Using streaming citation extraction for {} MB file", fileSize / (1024 * 1024));
                extractedMappings = extractCitationEntityMappingsStreaming(filePath, format);
            } else {
                String content = Files.readString(filePath, StandardCharsets.UTF_8);
                extractedMappings = extractCitationEntityMappings(content, format);
            }

            if (!extractedMappings.isEmpty()) {
                log.info("Extracted {} citation-entity mappings from uploaded file for project {}",
                         extractedMappings.size(), projectId);

                Path metadataFile = projectDir(projectId).resolve("citation-metadata.json");
                Files.createDirectories(metadataFile.getParent());
                objectMapper.writeValue(metadataFile.toFile(), extractedMappings);

                log.info("Stored citation metadata for project {}: {}", projectId, extractedMappings.keySet());
            } else {
                log.debug("No citations found in uploaded file for project {}", projectId);
            }
        } catch (IOException e) {
            log.error("Failed to extract citation mappings from file for project {}", projectId, e);
        }
    }

    private Map<String, String> extractCitationEntityMappingsStreaming(Path filePath, String format) throws IOException {
        Map<String, String> mappings = new HashMap<>();

        java.util.LinkedList<String> windowLines = new java.util.LinkedList<>();
        int windowSize = 50;
        int citationCount = 0;

        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                windowLines.addLast(line);
                if (windowLines.size() > windowSize) {
                    windowLines.removeFirst();
                }

                String citationUrn = extractCitationUrn(line, format);
                if (citationUrn != null) {
                    citationCount++;

                    String entityUri = null;
                    java.util.ListIterator<String> it = windowLines.listIterator(windowLines.size() - 1);
                    while (it.hasPrevious()) {
                        String prevLine = it.previous();
                        String entity = extractEntityFromLine(prevLine, format);
                        if (entity != null && !entity.isEmpty() && !entity.startsWith("urn:citation:")) {
                            entityUri = entity;
                            break;
                        }
                    }
                    if (entityUri != null) {
                        mappings.put(citationUrn, entityUri);
                    }
                }
            }
        }

        log.info("Streaming extraction complete: found {} citations, mapped {} to entities",
                citationCount, mappings.size());
        return mappings;
    }

    private Map<String, String> extractCitationEntityMappings(String content, String format) {
        Map<String, String> mappings = new HashMap<>();
        String[] lines = content.split("\n", -1);

        log.debug("Extracting citation mappings from {} lines in format: {}", lines.length, format);

        int citationCount = 0;
        for (int i = 0; i < lines.length; i++) {
            String citationUrn = extractCitationUrn(lines[i], format);
            if (citationUrn != null) {
                citationCount++;
                log.debug("Found citation '{}' at line {}", citationUrn, i);

                String entityUri = findNearestEntityBeforeLine(lines, i, format);
                if (entityUri != null && !entityUri.isEmpty()) {
                    mappings.put(citationUrn, entityUri);
                    log.info("Mapped citation '{}' to entity '{}'", citationUrn, entityUri);
                } else {
                    log.warn("Could not find entity for citation '{}' at line {}", citationUrn, i);
                }
            }
        }

        log.info("Extraction complete: found {} citations, mapped {} to entities",
                citationCount, mappings.size());

        return mappings;
    }

    private String findNearestEntityBeforeLine(String[] lines, int citationLine, String format) {

        int searchStart = Math.max(0, citationLine - 50);

        log.trace("Searching backwards from line {} to line {} for entity", citationLine, searchStart);

        for (int i = citationLine - 1; i >= searchStart; i--) {
            String line = lines[i];
            String entity = extractEntityFromLine(line, format);
            if (entity != null && !entity.isEmpty() && !entity.startsWith("urn:citation:")) {
                log.debug("Found entity '{}' at line {} (distance: {} lines from citation)",
                         entity, i, citationLine - i);
                return entity;
            }
        }

        log.debug("No entity found before citation at line {} (searched {} lines backwards)",
                 citationLine, citationLine - searchStart);
        return null;
    }

    private String extractEntityFromLine(String line, String format) {

        if (line.matches(".*<(https?://[^>]+|urn:[^>]+)>.*")) {
            String uri = line.replaceAll(".*<(https?://[^>]+|urn:[^>]+)>.*", "$1");
            if (!uri.contains("citation")) {
                return uri;
            }
        }

        if (line.matches(".*rdf:about=\"([^\"]+)\".*")) {
            return line.replaceAll(".*rdf:about=\"([^\"]+)\".*", "$1");
        }

        if (line.matches(".*IRI=\"([^\"]+)\".*")) {
            return line.replaceAll(".*IRI=\"([^\"]+)\".*", "$1");
        }

        if (line.matches(".*\\b([a-zA-Z_][a-zA-Z0-9_-]*:[a-zA-Z_][a-zA-Z0-9_-]+)\\b.*")) {
            String prefixedName = line.replaceAll(".*\\b([a-zA-Z_][a-zA-Z0-9_-]*:[a-zA-Z_][a-zA-Z0-9_-]+)\\b.*", "$1");

            if (!prefixedName.matches("(rdf|rdfs|owl|dc|bibo|prov|foaf|xsd):.*")) {
                return prefixedName;
            }
        }

        return null;
    }

    private String detectFormatFromPath(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".ttl")) return "turtle";
        if (fileName.endsWith(".nt")) return "ntriples";
        if (fileName.endsWith(".rdf")) return "rdfxml";
        if (fileName.endsWith(".owl")) return "rdfxml";
        if (fileName.endsWith(".owlxml")) return "owlxml";
        if (fileName.endsWith(".omn")) return "manchester";
        if (fileName.endsWith(".ofn")) return "functional";
        return "rdfxml";
    }

    public String repositionCitations(String content, Map<String, String> citationMappings, String format) {
        if (citationMappings.isEmpty()) {
            log.debug("No citation mappings to reposition");
            return content;
        }

        log.info("Repositioning {} citations for format: {}", citationMappings.size(), format);
        log.debug("Citation mappings: {}", citationMappings);

        String[] lines = content.split("\n", -1);
        List<String> outputLines = new ArrayList<>();
        Map<String, List<String>> citationBlocks = new HashMap<>();

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String citationUrn = extractCitationUrn(line, format);

            if (citationUrn != null) {

                List<String> block = extractCitationBlock(lines, i, format);
                citationBlocks.put(citationUrn, block);
                i += block.size();
                log.debug("Extracted citation block for: {} ({} lines, starting at line {})",
                         citationUrn, block.size(), i - block.size());
            } else {
                outputLines.add(line);
                i++;
            }
        }

        log.info("Extracted {} citation blocks, {} non-citation lines remain",
                 citationBlocks.size(), outputLines.size());

        Map<Integer, List<String>> citationsToInsertAfterLine = new HashMap<>();
        Map<String, Boolean> insertedCitations = new HashMap<>();

        for (Map.Entry<String, String> mapping : citationMappings.entrySet()) {
            String citationUrn = mapping.getKey();
            String entityUri = mapping.getValue();

            log.debug("Searching for entity '{}' for citation '{}'", entityUri, citationUrn);

            if (!citationBlocks.containsKey(citationUrn)) {
                log.warn("Citation block not found for: {}", citationUrn);
                continue;
            }

            boolean found = false;
            for (int lineIdx = 0; lineIdx < outputLines.size(); lineIdx++) {
                String line = outputLines.get(lineIdx);

                if (lineContainsEntity(line, entityUri, format)) {

                    int entityEndLine = findEntityEndLine(outputLines, lineIdx, format);

                    log.info("Found entity '{}' at line {}, ends at line {}",
                            entityUri, lineIdx, entityEndLine);

                    citationsToInsertAfterLine
                        .computeIfAbsent(entityEndLine, k -> new ArrayList<>())
                        .add(citationUrn);

                    insertedCitations.put(citationUrn, true);
                    found = true;

                    log.debug("Scheduled citation {} to be inserted after line {} (entity: {})",
                             citationUrn, entityEndLine, entityUri);
                    break;
                }
            }

            if (!found) {
                log.warn("Entity '{}' not found in content for citation '{}' - will append at end",
                        entityUri, citationUrn);
            }
        }

        log.info("Scheduled {} citations at {} different positions",
                 insertedCitations.size(), citationsToInsertAfterLine.size());

        List<String> result = new ArrayList<>();
        for (i = 0; i < outputLines.size(); i++) {
            result.add(outputLines.get(i));

            if (citationsToInsertAfterLine.containsKey(i)) {
                List<String> citationsToInsert = citationsToInsertAfterLine.get(i);

                for (String citationUrn : citationsToInsert) {
                    if (citationBlocks.containsKey(citationUrn)) {
                        List<String> citationBlock = citationBlocks.get(citationUrn);

                        result.add("");

                        result.addAll(citationBlock);

                        log.debug("Inserted citation {} after line {} ({} total lines now)",
                                 citationUrn, i, result.size());
                    }
                }

                result.add("");
            }
        }

        int appendedCount = 0;
        for (Map.Entry<String, String> mapping : citationMappings.entrySet()) {
            String citationUrn = mapping.getKey();
            if (!insertedCitations.getOrDefault(citationUrn, false) && citationBlocks.containsKey(citationUrn)) {
                log.warn("Could not find entity for citation {}, appending at end", citationUrn);
                result.add("");
                result.add("# Citation appended (entity not found: " + mapping.getValue() + ")");
                result.addAll(citationBlocks.get(citationUrn));
                appendedCount++;
            }
        }

        log.info("Repositioning complete: {} citations inserted near entities, {} appended at end, total {} lines",
                 insertedCitations.size() - appendedCount, appendedCount, result.size());

        return String.join("\n", result);
    }

    private String extractCitationUrn(String line, String format) {

        if (line.contains("urn:citation:")) {

            if (line.matches(".*<(urn:citation:[^>]+)>.*")) {
                return line.replaceAll(".*<(urn:citation:[^>]+)>.*", "$1");
            }

            if (line.matches(".*rdf:about=\"(urn:citation:[^\"]+)\".*")) {
                return line.replaceAll(".*rdf:about=\"(urn:citation:[^\"]+)\".*", "$1");
            }

            if (line.matches(".*IRI=\"(urn:citation:[^\"]+)\".*")) {
                return line.replaceAll(".*IRI=\"(urn:citation:[^\"]+)\".*", "$1");
            }
        }
        return null;
    }

    private List<String> extractCitationBlock(String[] lines, int startIndex, String format) {
        List<String> block = new ArrayList<>();

        if ("turtle".equalsIgnoreCase(format) || "ttl".equalsIgnoreCase(format)) {

            for (int i = startIndex; i < lines.length; i++) {
                block.add(lines[i]);
                if (lines[i].trim().endsWith(".")) {
                    break;
                }
            }
        } else if ("ntriples".equalsIgnoreCase(format) || "nt".equalsIgnoreCase(format)) {

            String citationUrn = extractCitationUrn(lines[startIndex], format);
            for (int i = startIndex; i < lines.length; i++) {
                if (lines[i].contains(citationUrn)) {
                    block.add(lines[i]);
                } else if (!block.isEmpty()) {
                    break;
                }
            }
        } else if ("rdfxml".equalsIgnoreCase(format) || "owl".equalsIgnoreCase(format)) {

            for (int i = startIndex; i < lines.length; i++) {
                block.add(lines[i]);
                if (lines[i].contains("</rdf:Description>") || lines[i].contains("/>")) {
                    break;
                }
            }
        } else {

            block.add(lines[startIndex]);
        }

        return block;
    }

    private boolean lineContainsEntity(String line, String entityUri, String format) {
        if (entityUri == null || entityUri.isEmpty()) {
            return false;
        }

        if (line.contains("<" + entityUri + ">")) {
            log.trace("Entity match (full URI in brackets): {}", entityUri);
            return true;
        }

        if (line.contains("\"" + entityUri + "\"")) {
            log.trace("Entity match (full URI in quotes): {}", entityUri);
            return true;
        }

        if (entityUri.contains(":") && !entityUri.contains("://")) {

            String pattern = "\\b" + entityUri.replace(":", "\\:") + "\\b";
            if (line.matches(".*" + pattern + ".*")) {
                log.trace("Entity match (prefixed name): {}", entityUri);
                return true;
            }
        }

        String localName = extractLocalName(entityUri);
        if (localName != null && !localName.isEmpty()) {

            if (line.matches(".*[:#]" + localName + "\\b.*")) {
                log.trace("Entity match (local name after : or #): {}", localName);
                return true;
            }

            if (line.contains("rdf:about") || line.contains("rdf:ID") || line.contains("rdf:resource")) {
                if (line.contains(localName)) {
                    log.trace("Entity match (local name in RDF attribute): {}", localName);
                    return true;
                }
            }

            if (line.contains("IRI=") || line.contains("abbreviatedIRI=")) {
                if (line.contains(localName)) {
                    log.trace("Entity match (local name in IRI attribute): {}", localName);
                    return true;
                }
            }
        }

        return false;
    }

    private String extractLocalName(String entityUri) {
        if (entityUri == null || entityUri.isEmpty()) {
            return null;
        }

        if (entityUri.contains("#")) {
            return entityUri.substring(entityUri.lastIndexOf('#') + 1);
        }

        if (entityUri.contains("/") && !entityUri.endsWith("/")) {
            return entityUri.substring(entityUri.lastIndexOf('/') + 1);
        }

        if (entityUri.contains(":") && !entityUri.contains("://")) {
            return entityUri.substring(entityUri.lastIndexOf(':') + 1);
        }

        return entityUri;
    }

    private int findEntityEndLine(List<String> lines, int startIndex, String format) {
        if (format == null || format.isEmpty()) {
            return startIndex;
        }

        String formatLower = format.toLowerCase();

        if ("turtle".equals(formatLower) || "ttl".equals(formatLower)) {

            for (int i = startIndex; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.endsWith(".") && !trimmed.endsWith("..")) {
                    log.trace("Entity ends at line {} (Turtle .)", i);
                    return i;
                }
            }
        } else if ("ntriples".equals(formatLower) || "nt".equals(formatLower)) {

            log.trace("Entity ends at line {} (N-Triples single line)", startIndex);
            return startIndex;
        } else if ("rdfxml".equals(formatLower) || "owl".equals(formatLower)) {

            int depth = 0;
            for (int i = startIndex; i < lines.size(); i++) {
                String line = lines.get(i);

                int opens = countOccurrences(line, "<") - countOccurrences(line, "/>");
                int closes = countOccurrences(line, "</") + countOccurrences(line, "/>");

                depth += opens - closes;

                if (depth <= 0 || line.contains("/>")) {
                    log.trace("Entity ends at line {} (RDF/XML closing tag)", i);
                    return i;
                }
            }
        } else if ("owlxml".equals(formatLower)) {

            for (int i = startIndex; i < lines.size(); i++) {
                if (lines.get(i).contains("</") || lines.get(i).trim().endsWith("/>")) {
                    log.trace("Entity ends at line {} (OWL/XML closing tag)", i);
                    return i;
                }
            }
        } else if ("manchester".equals(formatLower) || "manchestersyntax".equals(formatLower)) {

            for (int i = startIndex + 1; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.isEmpty() ||
                    trimmed.startsWith("Class:") ||
                    trimmed.startsWith("Individual:") ||
                    trimmed.startsWith("ObjectProperty:") ||
                    trimmed.startsWith("DataProperty:")) {
                    log.trace("Entity ends at line {} (Manchester blank/keyword)", i - 1);
                    return i - 1;
                }
            }
        } else if ("functional".equals(formatLower) || "functionalsyntax".equals(formatLower)) {

            int depth = 0;
            for (int i = startIndex; i < lines.size(); i++) {
                String line = lines.get(i);
                depth += countOccurrences(line, "(") - countOccurrences(line, ")");
                if (depth == 0) {
                    log.trace("Entity ends at line {} (Functional closing paren)", i);
                    return i;
                }
            }
        }

        log.trace("Entity end not found, defaulting to startIndex {}", startIndex);
        return startIndex;
    }

    private int countOccurrences(String str, String substr) {
        int count = 0;
        int index = 0;
        while ((index = str.indexOf(substr, index)) != -1) {
            count++;
            index += substr.length();
        }
        return count;
    }
}
