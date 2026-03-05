package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.ManchesterSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class StorageManager {

    private static final Logger log = LoggerFactory.getLogger(StorageManager.class);

    private final GraphDBDatasetService datasetService;
    private final Path projectsRoot;

    public StorageManager(@Value("${ontocode.data.dir:./data}") String rootDir,
                          GraphDBDatasetService datasetService) throws IOException {
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

    public Path exportOntology(String projectId, String format) throws IOException {
        // Always export fresh from GraphDB to get latest changes
        log.info("Exporting ontology from GraphDB for project: {}", projectId);
        if (requiresOwlApiFormat(format)) {
            return exportOntologyWithOwlApi(projectId, format);
        }

        RDFFormat rdfFormat = resolveLang(format);
        String extension = extensionFor(format);
        Path exportPath = projectDir(projectId).resolve("ontology.original." + extension);
        Files.createDirectories(exportPath.getParent());
        String content = datasetService.exportDataset(projectId, rdfFormat);
        Files.writeString(exportPath, content);
        log.info("Exported ontology to: {}", exportPath);
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
                || normalized.equals("functionalsyntax");
    }

    private Path exportOntologyWithOwlApi(String projectId, String format) throws IOException {
        String extension = extensionFor(format);
        Path exportPath = projectDir(projectId).resolve("ontology.original." + extension);
        Files.createDirectories(exportPath.getParent());

        String rdfXmlContent = datasetService.exportDataset(projectId, RDFFormat.RDFXML);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology;
        try (ByteArrayInputStream input = new ByteArrayInputStream(rdfXmlContent.getBytes(StandardCharsets.UTF_8))) {
            ontology = manager.loadOntologyFromOntologyDocument(input);
        } catch (OWLOntologyCreationException e) {
            throw new IOException("Failed to parse ontology for export: " + projectId, e);
        }

        OWLDocumentFormat documentFormat = resolveOwlApiFormat(format);
        try (OutputStream outputStream = Files.newOutputStream(exportPath)) {
            manager.saveOntology(ontology, documentFormat, outputStream);
        } catch (OWLOntologyStorageException e) {
            throw new IOException("Failed to export ontology in format: " + format, e);
        }

        log.info("Exported ontology to: {}", exportPath);
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

    /**
     * Store code view content to cache for preserving line positions.
     * This is used when the user inserts citations at specific lines.
     * The cached content is returned instead of re-exporting from GraphDB.
     * 
     * @param projectId The project identifier
     * @param content The content to cache
     * @param format The format (turtle, rdfxml, etc.)
     */
    public void storeCodeViewCache(String projectId, String content, String format) throws IOException {
        Path cacheFile = getCodeViewCachePath(projectId, format);
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, content, StandardCharsets.UTF_8);
        log.info("Stored code view cache for project {} in format {}: {} bytes", 
                 projectId, format, content.length());
    }

    /**
     * Retrieve cached code view content if available.
     * Returns empty if no cache exists for the format.
     * 
     * @param projectId The project identifier
     * @param format The format (turtle, rdfxml, etc.)
     * @return Optional containing the cached content, or empty if not cached
     */
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

    /**
     * Clear code view cache for a project (all formats).
     * Called when GraphDB content changes through other means.
     * 
     * @param projectId The project identifier
     */
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
    }

    /**
     * Clear code view cache for a specific format.
     * 
     * @param projectId The project identifier
     * @param format The format to clear
     */
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
}
