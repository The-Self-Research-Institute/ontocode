package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

final class CitationRepositioner {

    private static final Logger log = LoggerFactory.getLogger(CitationRepositioner.class);

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

    String extractCitationUrn(String line, String format) {
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
