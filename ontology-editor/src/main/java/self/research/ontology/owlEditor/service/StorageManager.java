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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

@Service
public class StorageManager {

    private static final Logger log = LoggerFactory.getLogger(StorageManager.class);

    private final GraphDBDatasetService datasetService;
    private final Path projectsRoot;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        
        // Apply smart repositioning if we have citation-entity mappings
        Map<String, String> citationMappings = getCitationEntityMappings(projectId);
        if (!citationMappings.isEmpty()) {
            log.info("Applying smart citation repositioning for {} citations", citationMappings.size());
            content = repositionCitations(content, citationMappings, format);
        }
        
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
        if (rdfXmlContent == null || rdfXmlContent.isBlank()) {
            throw new IOException("No RDF/XML content exported from GraphDB for project: " + projectId);
        }
        log.info("Exported {} bytes of RDF/XML from GraphDB for OWL API conversion to {}", rdfXmlContent.length(), format);

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology;
        try (ByteArrayInputStream input = new ByteArrayInputStream(rdfXmlContent.getBytes(StandardCharsets.UTF_8))) {
            ontology = manager.loadOntologyFromOntologyDocument(input);
        } catch (OWLOntologyCreationException e) {
            log.error("OWL API failed to parse RDF/XML for project {}: {}", projectId, e.getMessage());
            throw new IOException("Failed to parse ontology for export: " + projectId + " — " + e.getMessage(), e);
        }

        OWLDocumentFormat sourceFormat = manager.getOntologyFormat(ontology);
        OWLDocumentFormat documentFormat = resolveOwlApiFormat(format);

        // Copy prefix mappings from source to target format for readable output
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

    /**
     * Store citation-to-entity mappings for smart repositioning.
     * This metadata is used when exporting from GraphDB to position citations near their entities.
     * 
     * @param projectId The project identifier
     * @param citationUri The citation URN (e.g., urn:citation:xxx)
     * @param entityUri The entity this citation references (full URI or prefixed name)
     */
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

    /**
     * Retrieve citation-to-entity mappings for a project.
     * 
     * @param projectId The project identifier
     * @return Map of citation URN to entity URI
     */
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

    /**
     * Clear citation-entity mappings for a project.
     * 
     * @param projectId The project identifier
     */
    public void clearCitationEntityMappings(String projectId) {
        Path metadataFile = projectDir(projectId).resolve("citation-metadata.json");
        try {
            Files.deleteIfExists(metadataFile);
            log.info("Cleared citation metadata for project {}", projectId);
        } catch (IOException e) {
            log.warn("Failed to delete citation metadata for project {}", projectId, e);
        }
    }

    /**
     * Extract citation-to-entity mappings from an uploaded ontology file.
     * This method parses the file to find citations and their associated entities,
     * then stores the mappings for smart repositioning during export.
     * 
     * @param filePath Path to the uploaded ontology file
     * @param projectId The project identifier
     */
    public void extractCitationMappingsFromFile(Path filePath, String projectId) {
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            String format = detectFormatFromPath(filePath);
            
            Map<String, String> extractedMappings = extractCitationEntityMappings(content, format);
            
            if (!extractedMappings.isEmpty()) {
                log.info("Extracted {} citation-entity mappings from uploaded file for project {}", 
                         extractedMappings.size(), projectId);
                
                // Store all mappings at once
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

    /**
     * Extract citation-to-entity mappings from ontology content.
     * Scans backwards from each citation to find the nearest entity declaration.
     * 
     * @param content The ontology file content
     * @param format The ontology format
     * @return Map of citation URN to entity URI
     */
    private Map<String, String> extractCitationEntityMappings(String content, String format) {
        Map<String, String> mappings = new HashMap<>();
        String[] lines = content.split("\n", -1);
        
        log.debug("Extracting citation mappings from {} lines in format: {}", lines.length, format);
        
        // Find all citation lines
        int citationCount = 0;
        for (int i = 0; i < lines.length; i++) {
            String citationUrn = extractCitationUrn(lines[i], format);
            if (citationUrn != null) {
                citationCount++;
                log.debug("Found citation '{}' at line {}", citationUrn, i);
                
                // Search backwards to find the entity this citation references
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

    /**
     * Search backwards from a citation to find the nearest entity declaration.
     */
    private String findNearestEntityBeforeLine(String[] lines, int citationLine, String format) {
        // Search up to 50 lines backwards (covers most ontology patterns)
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

    /**
     * Extract entity URI from a line of ontology content.
     */
    private String extractEntityFromLine(String line, String format) {
        // Turtle/N-Triples: <http://example.org/Entity>
        if (line.matches(".*<(https?://[^>]+|urn:[^>]+)>.*")) {
            String uri = line.replaceAll(".*<(https?://[^>]+|urn:[^>]+)>.*", "$1");
            if (!uri.contains("citation")) {
                return uri;
            }
        }
        
        // RDF/XML: rdf:about="http://example.org/Entity"
        if (line.matches(".*rdf:about=\"([^\"]+)\".*")) {
            return line.replaceAll(".*rdf:about=\"([^\"]+)\".*", "$1");
        }
        
        // OWL/XML: IRI="http://example.org/Entity"
        if (line.matches(".*IRI=\"([^\"]+)\".*")) {
            return line.replaceAll(".*IRI=\"([^\"]+)\".*", "$1");
        }
        
        // Prefixed names: ex:Entity, owl:Class
        if (line.matches(".*\\b([a-zA-Z_][a-zA-Z0-9_-]*:[a-zA-Z_][a-zA-Z0-9_-]+)\\b.*")) {
            String prefixedName = line.replaceAll(".*\\b([a-zA-Z_][a-zA-Z0-9_-]*:[a-zA-Z_][a-zA-Z0-9_-]+)\\b.*", "$1");
            // Filter out common RDF/OWL predicates
            if (!prefixedName.matches("(rdf|rdfs|owl|dc|bibo|prov|foaf|xsd):.*")) {
                return prefixedName;
            }
        }
        
        return null;
    }

    /**
     * Detect ontology format from file path.
     */
    private String detectFormatFromPath(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".ttl")) return "turtle";
        if (fileName.endsWith(".nt")) return "ntriples";
        if (fileName.endsWith(".rdf")) return "rdfxml";
        if (fileName.endsWith(".owl")) return "rdfxml";
        if (fileName.endsWith(".owlxml")) return "owlxml";
        if (fileName.endsWith(".omn")) return "manchester";
        if (fileName.endsWith(".ofn")) return "functional";
        return "rdfxml"; // default
    }

    /**
     * Reposition citations in exported content to be near their referenced entities.
     * This ensures citations don't appear randomly when GraphDB reorganizes triples.
     * 
     * @param content The exported ontology content
     * @param citationMappings Map of citation URN to entity URI
     * @param format The ontology format (turtle, rdfxml, etc.)
     * @return Content with citations repositioned near their entities
     */
    public String repositionCitations(String content, Map<String, String> citationMappings, String format) {
        if (citationMappings.isEmpty()) {
            log.debug("No citation mappings to reposition");
            return content;
        }

        log.info("Repositioning {} citations for format: {}", citationMappings.size(), format);
        log.debug("Citation mappings: {}", citationMappings);
        
        // Split content into lines for processing
        String[] lines = content.split("\n", -1); // -1 to preserve trailing empty lines
        List<String> outputLines = new ArrayList<>();
        Map<String, List<String>> citationBlocks = new HashMap<>();
        
        // Step 1: Extract citation blocks from content
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String citationUrn = extractCitationUrn(line, format);
            
            if (citationUrn != null) {
                // Found start of a citation block - extract the entire block
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
        
        // Step 2: Build map of line numbers to citations that should be inserted after them
        Map<Integer, List<String>> citationsToInsertAfterLine = new HashMap<>();
        Map<String, Boolean> insertedCitations = new HashMap<>();
        
        // Find where each citation should go
        for (Map.Entry<String, String> mapping : citationMappings.entrySet()) {
            String citationUrn = mapping.getKey();
            String entityUri = mapping.getValue();
            
            log.debug("Searching for entity '{}' for citation '{}'", entityUri, citationUrn);
            
            if (!citationBlocks.containsKey(citationUrn)) {
                log.warn("Citation block not found for: {}", citationUrn);
                continue;
            }
            
            // Search for the entity in outputLines
            boolean found = false;
            for (int lineIdx = 0; lineIdx < outputLines.size(); lineIdx++) {
                String line = outputLines.get(lineIdx);
                
                if (lineContainsEntity(line, entityUri, format)) {
                    // Found the entity - find where it ends
                    int entityEndLine = findEntityEndLine(outputLines, lineIdx, format);
                    
                    log.info("Found entity '{}' at line {}, ends at line {}", 
                            entityUri, lineIdx, entityEndLine);
                    
                    // Schedule citation to be inserted after entity end
                    citationsToInsertAfterLine
                        .computeIfAbsent(entityEndLine, k -> new ArrayList<>())
                        .add(citationUrn);
                    
                    insertedCitations.put(citationUrn, true);
                    found = true;
                    
                    log.debug("Scheduled citation {} to be inserted after line {} (entity: {})", 
                             citationUrn, entityEndLine, entityUri);
                    break; // Found entity, no need to search further
                }
            }
            
            if (!found) {
                log.warn("Entity '{}' not found in content for citation '{}' - will append at end", 
                        entityUri, citationUrn);
            }
        }
        
        log.info("Scheduled {} citations at {} different positions", 
                 insertedCitations.size(), citationsToInsertAfterLine.size());
        
        // Step 3: Build result by inserting citations at scheduled positions
        List<String> result = new ArrayList<>();
        for (i = 0; i < outputLines.size(); i++) {
            result.add(outputLines.get(i));
            
            // Check if we should insert citations after this line
            if (citationsToInsertAfterLine.containsKey(i)) {
                List<String> citationsToInsert = citationsToInsertAfterLine.get(i);
                
                for (String citationUrn : citationsToInsert) {
                    if (citationBlocks.containsKey(citationUrn)) {
                        List<String> citationBlock = citationBlocks.get(citationUrn);
                        
                        // Add blank line before citation for readability
                        result.add("");
                        
                        // Add citation block
                        result.addAll(citationBlock);
                        
                        log.debug("Inserted citation {} after line {} ({} total lines now)", 
                                 citationUrn, i, result.size());
                    }
                }
                
                // Add blank line after citations
                result.add("");
            }
        }
        
        // Step 3: Append any citations that couldn't be positioned (entity not found)
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

    /**
     * Extract citation URN from a line if it contains a citation declaration.
     */
    private String extractCitationUrn(String line, String format) {
        // Match urn:citation:xxx in various formats
        if (line.contains("urn:citation:")) {
            // Turtle, N-Triples: <urn:citation:xxx>
            if (line.matches(".*<(urn:citation:[^>]+)>.*")) {
                return line.replaceAll(".*<(urn:citation:[^>]+)>.*", "$1");
            }
            // RDF/XML: rdf:about="urn:citation:xxx"
            if (line.matches(".*rdf:about=\"(urn:citation:[^\"]+)\".*")) {
                return line.replaceAll(".*rdf:about=\"(urn:citation:[^\"]+)\".*", "$1");
            }
            // OWL/XML: IRI="urn:citation:xxx"
            if (line.matches(".*IRI=\"(urn:citation:[^\"]+)\".*")) {
                return line.replaceAll(".*IRI=\"(urn:citation:[^\"]+)\".*", "$1");
            }
        }
        return null;
    }

    /**
     * Extract entire citation block starting from the given line.
     */
    private List<String> extractCitationBlock(String[] lines, int startIndex, String format) {
        List<String> block = new ArrayList<>();
        
        if ("turtle".equalsIgnoreCase(format) || "ttl".equalsIgnoreCase(format)) {
            // Turtle: Read until we find a line ending with '.' (end of subject)
            for (int i = startIndex; i < lines.length; i++) {
                block.add(lines[i]);
                if (lines[i].trim().endsWith(".")) {
                    break;
                }
            }
        } else if ("ntriples".equalsIgnoreCase(format) || "nt".equalsIgnoreCase(format)) {
            // N-Triples: Each line is a triple, collect all with same subject
            String citationUrn = extractCitationUrn(lines[startIndex], format);
            for (int i = startIndex; i < lines.length; i++) {
                if (lines[i].contains(citationUrn)) {
                    block.add(lines[i]);
                } else if (!block.isEmpty()) {
                    break;
                }
            }
        } else if ("rdfxml".equalsIgnoreCase(format) || "owl".equalsIgnoreCase(format)) {
            // RDF/XML: Read until closing tag
            for (int i = startIndex; i < lines.length; i++) {
                block.add(lines[i]);
                if (lines[i].contains("</rdf:Description>") || lines[i].contains("/>")) {
                    break;
                }
            }
        } else {
            // Default: Single line
            block.add(lines[startIndex]);
        }
        
        return block;
    }

    /**
     * Check if a line contains the specified entity.
     * Uses multiple pattern matching strategies for robustness.
     */
    private boolean lineContainsEntity(String line, String entityUri, String format) {
        if (entityUri == null || entityUri.isEmpty()) {
            return false;
        }
        
        // Strategy 1: Exact full URI match in angle brackets
        if (line.contains("<" + entityUri + ">")) {
            log.trace("Entity match (full URI in brackets): {}", entityUri);
            return true;
        }
        
        // Strategy 2: Exact URI in quotes (RDF/XML attributes)
        if (line.contains("\"" + entityUri + "\"")) {
            log.trace("Entity match (full URI in quotes): {}", entityUri);
            return true;
        }
        
        // Strategy 3: Prefixed name match (exact)
        if (entityUri.contains(":") && !entityUri.contains("://")) {
            // It's a prefixed name like ex:Person
            String pattern = "\\b" + entityUri.replace(":", "\\:") + "\\b";
            if (line.matches(".*" + pattern + ".*")) {
                log.trace("Entity match (prefixed name): {}", entityUri);
                return true;
            }
        }
        
        // Strategy 4: Extract local name and check for it
        String localName = extractLocalName(entityUri);
        if (localName != null && !localName.isEmpty()) {
            // For Turtle: check for local name after : or #
            if (line.matches(".*[:#]" + localName + "\\b.*")) {
                log.trace("Entity match (local name after : or #): {}", localName);
                return true;
            }
            
            // For RDF/XML: check in rdf:about, rdf:ID, rdf:resource
            if (line.contains("rdf:about") || line.contains("rdf:ID") || line.contains("rdf:resource")) {
                if (line.contains(localName)) {
                    log.trace("Entity match (local name in RDF attribute): {}", localName);
                    return true;
                }
            }
            
            // For OWL/XML: check in IRI or abbreviatedIRI
            if (line.contains("IRI=") || line.contains("abbreviatedIRI=")) {
                if (line.contains(localName)) {
                    log.trace("Entity match (local name in IRI attribute): {}", localName);
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Extract local name from entity URI.
     * Examples:
     *   http://example.org/Person -> Person
     *   http://example.org#Person -> Person
     *   ex:Person -> Person
     */
    private String extractLocalName(String entityUri) {
        if (entityUri == null || entityUri.isEmpty()) {
            return null;
        }
        
        // Check for fragment (#)
        if (entityUri.contains("#")) {
            return entityUri.substring(entityUri.lastIndexOf('#') + 1);
        }
        
        // Check for path separator (/)
        if (entityUri.contains("/") && !entityUri.endsWith("/")) {
            return entityUri.substring(entityUri.lastIndexOf('/') + 1);
        }
        
        // Check for prefixed name (ex:Person)
        if (entityUri.contains(":") && !entityUri.contains("://")) {
            return entityUri.substring(entityUri.lastIndexOf(':') + 1);
        }
        
        return entityUri; // Return as-is if no separator found
    }

    /**
     * Find the line where an entity's declaration ends.
     * Searches forward from startIndex to find the closing marker for the entity.
     */
    private int findEntityEndLine(List<String> lines, int startIndex, String format) {
        if (format == null || format.isEmpty()) {
            return startIndex;
        }
        
        String formatLower = format.toLowerCase();
        
        if ("turtle".equals(formatLower) || "ttl".equals(formatLower)) {
            // Turtle: Find the closing '.' that ends the subject
            // In Turtle, a subject's triples end with '.' on its own or at end of line
            for (int i = startIndex; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.endsWith(".") && !trimmed.endsWith("..")) {
                    log.trace("Entity ends at line {} (Turtle .)", i);
                    return i;
                }
            }
        } else if ("ntriples".equals(formatLower) || "nt".equals(formatLower)) {
            // N-Triples: Each line is a complete triple, so entity ends same line
            log.trace("Entity ends at line {} (N-Triples single line)", startIndex);
            return startIndex;
        } else if ("rdfxml".equals(formatLower) || "owl".equals(formatLower)) {
            // RDF/XML: Find closing tag (</...> or />)
            int depth = 0;
            for (int i = startIndex; i < lines.size(); i++) {
                String line = lines.get(i);
                
                // Count opening tags (not self-closing)
                int opens = countOccurrences(line, "<") - countOccurrences(line, "/>");
                int closes = countOccurrences(line, "</") + countOccurrences(line, "/>");
                
                depth += opens - closes;
                
                if (depth <= 0 || line.contains("/>")) {
                    log.trace("Entity ends at line {} (RDF/XML closing tag)", i);
                    return i;
                }
            }
        } else if ("owlxml".equals(formatLower)) {
            // OWL/XML: Similar to RDF/XML
            for (int i = startIndex; i < lines.size(); i++) {
                if (lines.get(i).contains("</") || lines.get(i).trim().endsWith("/>")) {
                    log.trace("Entity ends at line {} (OWL/XML closing tag)", i);
                    return i;
                }
            }
        } else if ("manchester".equals(formatLower) || "manchestersyntax".equals(formatLower)) {
            // Manchester: Entity ends at blank line or next declaration keyword
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
            // Functional: Find matching closing parenthesis
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
        return startIndex; // Default to same line if can't determine
    }
    
    /**
     * Count occurrences of a substring in a string.
     */
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
