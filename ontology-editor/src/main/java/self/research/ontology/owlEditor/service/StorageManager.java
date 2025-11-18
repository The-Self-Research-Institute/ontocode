package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
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
        RDFFormat rdfFormat = resolveLang(format);
        String extension = extensionFor(format);
        Path exportPath = projectDir(projectId).resolve("ontology.current." + extension);
        Files.createDirectories(exportPath.getParent());
        String content = datasetService.exportDataset(projectId, rdfFormat);
        Files.writeString(exportPath, content);
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

    private String extensionFor(String format) {
        if (format == null) {
            return "owl";
        }
        return switch (format.toLowerCase()) {
            case "ttl", "turtle" -> "ttl";
            case "nt", "ntriples" -> "nt";
            case "jsonld" -> "jsonld";
            case "rdfxml", "xml" -> "owl";
            default -> format.toLowerCase();
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
}