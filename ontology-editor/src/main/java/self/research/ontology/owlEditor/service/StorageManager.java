package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class StorageManager {

    private static final Logger log = LoggerFactory.getLogger(StorageManager.class);

    private final Path projectsRoot;
    private final CitationPositions citations;
    private final OntologyExporter exporter;
    private final CodeViewFileStore codeViewFiles;

    public StorageManager(@Value("${ontocode.data.dir:./data}") String rootDir,
                          SparqlDatasetService datasetService) throws IOException {
        this.projectsRoot = Path.of(rootDir).toAbsolutePath().normalize().resolve("projects");
        Files.createDirectories(this.projectsRoot);
        this.citations = new CitationPositions(this::projectDir);
        this.exporter = new OntologyExporter(datasetService, this::projectDir, citations);
        this.codeViewFiles = new CodeViewFileStore(this::projectDir, exporter);
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

    /** Scratch directory for in-progress chunked uploads, keyed by client-generated upload session id. */
    public Path chunkUploadDir(String uploadId) throws IOException {
        Path dir = projectsRoot.getParent().resolve("chunk-uploads").resolve(uploadId);
        Files.createDirectories(dir);
        return dir;
    }

    /** Root of all in-progress chunked uploads — used by the cleanup sweep to find abandoned sessions. */
    public Path chunkUploadsRoot() throws IOException {
        Path dir = projectsRoot.getParent().resolve("chunk-uploads");
        Files.createDirectories(dir);
        return dir;
    }

    public void writeRestoreSnapshot(String projectId, Path target) throws IOException  {
        exporter.writeRestoreSnapshot(projectId, target);
    }

    public Path exportOntology(String projectId, String format) throws IOException  {
        return exporter.exportOntology(projectId, format);
    }

    public Path exportOntologyForJob(String projectId, String format) throws IOException  {
        return exporter.exportOntologyForJob(projectId, format);
    }

    public String extensionFor(String format) {
        return OntologyExporter.extensionFor(format);
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

    // ── Desktop draft (unsaved working copy) ─────────────────────────────────
    // Mutations autosave into draft/ontology.draft.owl; an explicit Save promotes
    // the draft to ontology.current.owl. A draft left on disk after a crash or
    // unsaved exit is the recovery source for the next open.

    public Path draftDir(String projectId) {
        return projectDir(projectId).resolve("draft");
    }

    public Path draftOntologyPath(String projectId) {
        return draftDir(projectId).resolve("ontology.draft.owl");
    }

    public boolean hasDraft(String projectId) {
        return Files.exists(draftOntologyPath(projectId));
    }

    /**
     * Working state of the project: the unsaved draft when present, otherwise the
     * last saved ontology. Fuseki sync and OWLAPI warm should read this so views
     * always mirror what the user is editing.
     */
    public Optional<Path> findWorkingOntology(String projectId) {
        Path draft = draftOntologyPath(projectId);
        if (Files.exists(draft)) {
            return Optional.of(draft);
        }
        return findCurrentOntology(projectId);
    }

    /**
     * Explicit Save: atomically promote the draft to ontology.current.owl and
     * remove the draft folder. No-op (returns false) when no draft exists.
     */
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

    /** Discard unsaved changes: delete the draft folder entirely. */
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

    public record CodeViewPage(String content, long startLine, int lineCount, long totalLines, long totalBytes) {}

    public void storeCodeViewCache(String projectId, String content, String format) throws IOException  {
        codeViewFiles.storeCodeViewCache(projectId, content, format);
    }

    public void storeCodeViewCacheFile(String projectId, String format, Path source) throws IOException  {
        codeViewFiles.storeCodeViewCacheFile(projectId, format, source);
    }

    public Optional<String> getCodeViewCache(String projectId, String format) {
        return codeViewFiles.getCodeViewCache(projectId, format);
    }

    public void clearCodeViewCache(String projectId) {
        codeViewFiles.clearCodeViewCache(projectId);
    }

    public void setPublicGraphVersionStore(PublicGraphVersionStore publicGraphVersionStore) {
        codeViewFiles.setPublicGraphVersionStore(publicGraphVersionStore);
    }

    public long getPublicGraphVersion(String projectId) {
        return codeViewFiles.getPublicGraphVersion(projectId);
    }

    public void clearCodeViewCacheFormat(String projectId, String format) {
        codeViewFiles.clearCodeViewCacheFormat(projectId, format);
    }

    public Path ensureCodeViewFile(String projectId, String format) throws IOException  {
        return codeViewFiles.ensureCodeViewFile(projectId, format);
    }

    public CodeViewPage readCodeViewPage(String projectId, String format, long startLine, int lineCount) throws IOException  {
        return codeViewFiles.readCodeViewPage(projectId, format, startLine, lineCount);
    }

    public void storeCitationEntityMapping(String projectId, String citationUri, String entityUri) throws IOException  {
        citations.storeCitationEntityMapping(projectId, citationUri, entityUri);
    }

    public Map<String, String> getCitationEntityMappings(String projectId) {
        return citations.getCitationEntityMappings(projectId);
    }

    public void clearCitationEntityMappings(String projectId) {
        citations.clearCitationEntityMappings(projectId);
    }

    public void extractCitationMappingsFromFile(Path filePath, String projectId) {
        citations.extractCitationMappingsFromFile(filePath, projectId);
    }

    public String repositionCitations(String content, Map<String, String> citationMappings, String format) {
        return citations.repositionCitations(content, citationMappings, format);
    }
}
