package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class CitationPositions {

    private static final Logger log = LoggerFactory.getLogger(CitationPositions.class);

    private final Function<String, Path> projectDirs;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CitationRepositioner repositioner = new CitationRepositioner();

    CitationPositions(Function<String, Path> projectDirs) {
        this.projectDirs = projectDirs;
    }

    public void storeCitationEntityMapping(String projectId, String citationUri, String entityUri) throws IOException {
        if (citationUri == null || entityUri == null) {
            log.warn("Cannot store null citation-entity mapping for project {}", projectId);
            return;
        }
        
        Map<String, String> mappings = getCitationEntityMappings(projectId);
        mappings.put(citationUri, entityUri);
        
        Path metadataFile = projectDirs.apply(projectId).resolve("citation-metadata.json");
        Files.createDirectories(metadataFile.getParent());
        objectMapper.writeValue(metadataFile.toFile(), mappings);
        
        log.info("Stored citation-entity mapping: {} -> {} for project {}", citationUri, entityUri, projectId);
    }

    public Map<String, String> getCitationEntityMappings(String projectId) {
        Path metadataFile = projectDirs.apply(projectId).resolve("citation-metadata.json");
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
        Path metadataFile = projectDirs.apply(projectId).resolve("citation-metadata.json");
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
                
                Path metadataFile = projectDirs.apply(projectId).resolve("citation-metadata.json");
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
                
                String citationUrn = repositioner.extractCitationUrn(line, format);
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
            String citationUrn = repositioner.extractCitationUrn(lines[i], format);
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
            if (!prefixedName.matches("(rdf|rdfs|owl|dc|bibo|prov|foaf|xsd|xmlns|xml):.*")) {
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
        return repositioner.repositionCitations(content, citationMappings, format);
    }
}
