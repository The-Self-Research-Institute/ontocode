package self.research.ontology.owlEditor.util;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.*;
import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Utility to convert OWL ontologies from various formats to RDF/XML
 * Handles OWL Functional Syntax, Manchester Syntax, and other formats
 * that GraphDB cannot parse directly.
 */
public class OWLFormatConverter {
    
    private static final Logger log = LoggerFactory.getLogger(OWLFormatConverter.class);
    
    /**
     * Create an OWL Ontology Manager configured to ignore missing imports.
     * This prevents failures when imported ontologies are not accessible over the network.
     */
    private static OWLOntologyManager createManagerWithSilentImports() {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();

        // Prevent all network access for import resolution - this is the #1 performance killer.
        // Without this, OWL API tries to HTTP-fetch every <Import> URL and waits for timeout.
        manager.getIRIMappers().clear();

        OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                .setConnectionTimeout(1000); // 1 second max if any network call still happens
        manager.setOntologyLoaderConfiguration(config);

        manager.addMissingImportListener(event -> {
            log.info("Skipped import: {}", event.getImportedOntologyURI());
        });

        return manager;
    }
    
    /**
     * Check if a file needs format conversion by attempting to detect its format
     * Returns true if the file is in a format that needs conversion to RDF/XML
     */
    public static boolean needsConversion(Path filePath) {
        try {
            OWLOntologyManager manager = createManagerWithSilentImports();
            
            // Try to load and detect format
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(filePath.toFile());
            OWLDocumentFormat format = manager.getOntologyFormat(ontology);
            
            if (format == null) {
                log.info("Could not detect format for {}, assuming conversion needed", filePath.getFileName());
                return true;
            }
            
            // Check if it's already in RDF/XML format
            boolean isRdfXml = format instanceof RDFXMLDocumentFormat;
            boolean isTurtle = format instanceof TurtleDocumentFormat;
            boolean isNTriples = format instanceof NTriplesDocumentFormat;
            
            if (isRdfXml) {
                log.info("File {} is already in RDF/XML format", filePath.getFileName());
                return false;
            }
            
            if (isTurtle || isNTriples) {
                log.info("File {} is in {} format (supported by GraphDB)", 
                    filePath.getFileName(), format.getClass().getSimpleName());
                return false;
            }
            
            // Any other format (Functional, Manchester, OBO, etc.) needs conversion
            log.info("File {} is in {} format, needs conversion to RDF/XML", 
                filePath.getFileName(), format.getClass().getSimpleName());
            return true;
            
        } catch (OWLOntologyCreationException e) {
            log.warn("Failed to detect format for {}: {}. Will attempt conversion.", 
                filePath.getFileName(), e.getMessage());
            return true;
        }
    }
    
    /**
     * Convert an OWL file from any format to RDF/XML
     * Creates a new file with "-converted.owl" suffix
     * 
     * @param inputPath Path to input OWL file
     * @return Path to converted file, or original path if conversion not needed
     * @throws OWLOntologyCreationException if file cannot be loaded
     * @throws OWLOntologyStorageException if conversion fails
     * @throws IOException if file I/O fails
     */
    public static Path convertToRDFXML(Path inputPath)
            throws OWLOntologyCreationException, OWLOntologyStorageException, IOException {

        log.info("Starting OWL format conversion for: {}", inputPath.getFileName());

        long inputSize = Files.size(inputPath);
        log.info("Input file size: {} bytes", inputSize);

        if (inputSize == 0) {
            throw new IOException("Input file is empty (0 bytes): " + inputPath);
        }

        // Fix: strip any binary garbage prepended before the XML declaration.
        // The upload pipeline sometimes prepends binary bytes (e.g. from multipart encoding).
        Path fileToLoad = stripBinaryPrefix(inputPath);

        // Create OWL API manager with silent import handling
        OWLOntologyManager manager = createManagerWithSilentImports();

        // Load ontology (OWL API will auto-detect format)
        long loadStart = System.nanoTime();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(fileToLoad.toFile());
        long loadDuration = (System.nanoTime() - loadStart) / 1_000_000;
        log.info("Loaded ontology in {} ms. Axioms: {}", loadDuration, ontology.getAxiomCount());
        
        // Get original format info
        OWLDocumentFormat originalFormat = manager.getOntologyFormat(ontology);
        log.info("Original format: {}", 
            originalFormat != null ? originalFormat.getClass().getSimpleName() : "Unknown");
        
        // Create output path
        String filename = inputPath.getFileName().toString();
        String baseName = filename.replaceFirst("\\.[^.]+$", "");
        Path outputPath = inputPath.getParent().resolve(baseName + "-converted.owl");
        
        // Configure RDF/XML format
        RDFXMLDocumentFormat rdfXmlFormat = new RDFXMLDocumentFormat();
        
        // Preserve prefixes if available
        if (originalFormat != null && originalFormat.isPrefixOWLDocumentFormat()) {
            PrefixDocumentFormat prefixFormat = originalFormat.asPrefixOWLDocumentFormat();
            prefixFormat.getPrefixName2PrefixMap().forEach(rdfXmlFormat::setPrefix);
            log.info("Preserved {} namespace prefixes", prefixFormat.getPrefixName2PrefixMap().size());
        }
        
        // Save as RDF/XML
        long saveStart = System.nanoTime();
        try (FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
            manager.saveOntology(ontology, rdfXmlFormat, fos);
        }
        long saveDuration = (System.nanoTime() - saveStart) / 1_000_000;
        
        long fileSize = Files.size(outputPath);
        log.info("✓ Converted to RDF/XML in {} ms. Output: {} ({} bytes)",
            saveDuration, outputPath.getFileName(), fileSize);

        // Clean up the stripped temp file if one was created
        if (!fileToLoad.equals(inputPath)) {
            try {
                Files.deleteIfExists(fileToLoad);
            } catch (Exception e) {
                log.warn("Could not delete temp stripped file: {}", e.getMessage());
            }
        }

        return outputPath;
    }

    /**
     * Strip binary garbage bytes that may be prepended before the actual XML/OWL content.
     * This can happen when the upload pipeline (multipart encoding, gateway proxy) corrupts the file.
     * Returns the original path if the file is clean, or a new stripped temp file path.
     */
    private static Path stripBinaryPrefix(Path filePath) throws IOException {
        byte[] content = Files.readAllBytes(filePath);

        // Log first 16 bytes for diagnostics
        StringBuilder hexDump = new StringBuilder();
        for (int i = 0; i < Math.min(16, content.length); i++) {
            hexDump.append(String.format("%02X ", content[i]));
        }
        log.info("File header hex (first 16 bytes): {}", hexDump.toString().trim());

        // Check if file already starts with valid XML
        if (content.length > 0 && (content[0] == '<' || content[0] == '@')) {
            log.info("File starts with '{}', no stripping needed", (char) content[0]);
            return filePath;
        }

        // Check for UTF-8 BOM (EF BB BF) followed by '<' or '@'
        if (content.length > 3 && content[0] == (byte) 0xEF && content[1] == (byte) 0xBB
                && content[2] == (byte) 0xBF && (content[3] == '<' || content[3] == '@')) {
            log.info("File has UTF-8 BOM, stripping 3 bytes");
            Path stripped = filePath.getParent().resolve("stripped-" + filePath.getFileName());
            Files.write(stripped, Arrays.copyOfRange(content, 3, content.length));
            return stripped;
        }

        // Search for <?xml or <Ontology or <rdf:RDF or Turtle markers in the first 1024 bytes
        String header = new String(content, 0, Math.min(1024, content.length),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        int contentStart = header.indexOf("<?xml");
        if (contentStart < 0) {
            contentStart = header.indexOf("<Ontology");
        }
        if (contentStart < 0) {
            contentStart = header.indexOf("<rdf:RDF");
        }
        // Also detect Turtle format markers
        if (contentStart < 0) {
            contentStart = header.indexOf("@prefix");
        }
        if (contentStart < 0) {
            contentStart = header.indexOf("@base");
        }

        if (contentStart > 0) {
            log.warn("Found {} bytes of binary garbage before content. Stripping prefix.", contentStart);
            log.info("Garbage bytes: {}",
                hexDump(content, 0, Math.min(contentStart, 32)));
            Path stripped = filePath.getParent().resolve("stripped-" + filePath.getFileName());
            Files.write(stripped, Arrays.copyOfRange(content, contentStart, content.length));
            log.info("Wrote stripped file: {} ({} bytes)", stripped.getFileName(), content.length - contentStart);
            return stripped;
        }

        // No known marker found - return original and let OWL API try to parse
        log.info("No binary prefix detected or file is not a recognized format");
        return filePath;
    }

    /**
     * Sanitize a file on disk by stripping any binary garbage bytes before the actual content.
     * Overwrites the file in place if garbage is found.
     * Safe to call on any file - returns immediately if the file is already clean.
     */
    public static void sanitizeFileOnDisk(Path filePath) throws IOException {
        Path stripped = stripBinaryPrefix(filePath);
        if (!stripped.equals(filePath)) {
            // Stripped file was created - replace original with it
            Files.move(stripped, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("Sanitized file in place: {}", filePath.getFileName());
        }
        
        // Fix malformed RDF/XML structure (embedded elements in opening tag, appended documents)
        fixMalformedRdfXml(filePath);
        
        // Re-serialize with OWL API to fix namespace issues, malformed tags, etc.
        reserializeWithOwlApi(filePath);
    }
    
    /**
     * Fix common RDF/XML malformations at the text level before XML parsing.
     * Handles:
     * 1. Elements/comments embedded inside the <rdf:RDF ...> opening tag (e.g. Zotero citations)
     * 2. Duplicate XML documents appended after </rdf:RDF>
     */
    private static void fixMalformedRdfXml(Path filePath) throws IOException {
        String content;
        try {
            content = Files.readString(filePath);
        } catch (java.nio.charset.MalformedInputException e) {
            // File contains non-UTF-8 bytes (e.g. binary prefix remnants) — read with tolerant charset
            content = new String(Files.readAllBytes(filePath), java.nio.charset.StandardCharsets.ISO_8859_1);
        }
        
        if (!content.contains("<rdf:RDF")) {
            return;
        }
        
        String original = content;
        
        // Fix 1: Extract content embedded inside the <rdf:RDF ...> opening tag
        content = extractEmbeddedContentFromRdfTag(content);
        
        // Fix 2: Merge duplicate XML documents appended after </rdf:RDF>
        content = mergeAppendedRdfDocuments(content);
        
        if (!content.equals(original)) {
            log.info("Fixed malformed RDF/XML structure in: {}", filePath.getFileName());
            Files.writeString(filePath, content);
        }
    }
    
    /**
     * Fix <rdf:RDF> opening tag that has XML comments or elements (e.g. citations)
     * embedded between namespace declarations. Extracts them and places after the tag.
     */
    private static String extractEmbeddedContentFromRdfTag(String content) {
        int rdfStart = content.indexOf("<rdf:RDF");
        if (rdfStart < 0) return content;
        
        String prefix = content.substring(0, rdfStart);
        String fromRdf = content.substring(rdfStart);
        int len = fromRdf.length();
        
        int i = "<rdf:RDF".length();
        StringBuilder nsDecls = new StringBuilder();
        StringBuilder displaced = new StringBuilder();
        boolean hasDisplaced = false;
        
        while (i < len) {
            // Skip whitespace
            while (i < len && Character.isWhitespace(fromRdf.charAt(i))) {
                i++;
            }
            if (i >= len) break;
            
            char c = fromRdf.charAt(i);
            
            // Case 1: xmlns attribute
            if (fromRdf.startsWith("xmlns", i)) {
                int eq = fromRdf.indexOf('=', i);
                if (eq > 0 && eq < i + 80) {
                    int qStart = fromRdf.indexOf('"', eq);
                    if (qStart > 0 && qStart < eq + 5) {
                        int qEnd = fromRdf.indexOf('"', qStart + 1);
                        if (qEnd > 0) {
                            nsDecls.append("\t").append(fromRdf, i, qEnd + 1).append("\n");
                            i = qEnd + 1;
                            continue;
                        }
                    }
                }
            }
            
            // Case 2: XML comment <!-- ... -->
            if (fromRdf.startsWith("<!--", i)) {
                int commentEnd = fromRdf.indexOf("-->", i + 4);
                if (commentEnd >= 0) {
                    displaced.append(fromRdf, i, commentEnd + 3).append("\n");
                    i = commentEnd + 3;
                    hasDisplaced = true;
                    continue;
                }
            }
            
            // Case 3: Element start (not comment, not PI)
            if (c == '<' && i + 1 < len
                    && fromRdf.charAt(i + 1) != '!' && fromRdf.charAt(i + 1) != '?') {
                // Extract tag name
                int nameStart = i + 1;
                int nameEnd = nameStart;
                while (nameEnd < len && !Character.isWhitespace(fromRdf.charAt(nameEnd))
                        && fromRdf.charAt(nameEnd) != '>' && fromRdf.charAt(nameEnd) != '/') {
                    nameEnd++;
                }
                String tagName = fromRdf.substring(nameStart, nameEnd);
                
                // Find matching close tag </tagName>
                String closeTag = "</" + tagName + ">";
                int closePos = fromRdf.indexOf(closeTag, i);
                if (closePos >= 0) {
                    int elemEnd = closePos + closeTag.length();
                    displaced.append(fromRdf, i, elemEnd).append("\n");
                    i = elemEnd;
                    hasDisplaced = true;
                    continue;
                }
                
                // Try self-closing />
                int selfClose = fromRdf.indexOf("/>", i);
                if (selfClose >= 0 && selfClose < i + 500) {
                    displaced.append(fromRdf, i, selfClose + 2).append("\n");
                    i = selfClose + 2;
                    hasDisplaced = true;
                    continue;
                }
            }
            
            // Case 4: Closing > of <rdf:RDF> tag
            if (c == '>') {
                if (hasDisplaced) {
                    log.info("Extracted {} chars of embedded content from <rdf:RDF> opening tag",
                            displaced.length());
                    return prefix + "<rdf:RDF\n"
                            + nsDecls.toString().stripTrailing() + ">\n\n"
                            + displaced.toString() + "\n"
                            + fromRdf.substring(i + 1);
                }
                return content;
            }
            
            i++;
        }
        
        return content;
    }
    
    /**
     * Merge content from a second RDF/XML document appended after the first </rdf:RDF>.
     * This can happen when citation injection appends a separate XML document.
     */
    private static String mergeAppendedRdfDocuments(String content) {
        int firstClose = content.indexOf("</rdf:RDF>");
        if (firstClose < 0) return content;
        
        String afterFirst = content.substring(firstClose + "</rdf:RDF>".length());
        
        int secondRdfStart = afterFirst.indexOf("<rdf:RDF");
        if (secondRdfStart < 0) return content;
        
        log.info("Found appended RDF document after </rdf:RDF>, merging content");
        
        int secondTagClose = afterFirst.indexOf('>', secondRdfStart + "<rdf:RDF".length());
        if (secondTagClose < 0) return content;
        
        int secondClose = afterFirst.indexOf("</rdf:RDF>", secondTagClose);
        if (secondClose < 0) return content;
        
        String secondBody = afterFirst.substring(secondTagClose + 1, secondClose).trim();
        if (secondBody.isEmpty()) return content;
        
        return content.substring(0, firstClose) + "\n" + secondBody + "\n\n</rdf:RDF>\n";
    }
    
    /**
     * Load the file with OWL API (which is tolerant of namespace issues) and
     * re-serialize it as clean RDF/XML. This fixes all namespace declaration problems,
     * malformed XML comments, and other structural issues.
     */
    private static void reserializeWithOwlApi(Path filePath) throws IOException {
        String content;
        try {
            content = Files.readString(filePath);
        } catch (java.nio.charset.MalformedInputException e) {
            content = new String(Files.readAllBytes(filePath), java.nio.charset.StandardCharsets.ISO_8859_1);
        }
        
        // Only process files that look like RDF/XML
        if (!content.contains("<rdf:RDF") && !content.contains("<?xml")) {
            log.info("File does not look like RDF/XML, skipping OWL API re-serialization");
            return;
        }
        
        log.info("Re-serializing file with OWL API to fix namespace issues: {}", filePath.getFileName());
        
        try {
            OWLOntologyManager manager = createManagerWithSilentImports();
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(filePath.toFile());
            log.info("OWL API loaded ontology: {} axioms", ontology.getAxiomCount());
            
            // Get original format and prefixes
            OWLDocumentFormat originalFormat = manager.getOntologyFormat(ontology);
            
            // Create clean RDF/XML format
            RDFXMLDocumentFormat rdfXmlFormat = new RDFXMLDocumentFormat();
            
            // Copy existing prefixes
            if (originalFormat != null && originalFormat.isPrefixOWLDocumentFormat()) {
                PrefixDocumentFormat prefixFormat = originalFormat.asPrefixOWLDocumentFormat();
                prefixFormat.getPrefixName2PrefixMap().forEach(rdfXmlFormat::setPrefix);
            }
            
            // Write clean RDF/XML back to the file
            try (OutputStream out = Files.newOutputStream(filePath)) {
                manager.saveOntology(ontology, rdfXmlFormat, out);
            }
            
            log.info("Successfully re-serialized file as clean RDF/XML: {}", filePath.getFileName());
            
        } catch (Exception e) {
            log.warn("OWL API re-serialization failed (will try original file): {}", e.getMessage());
            // Don't throw - let the original file be used as-is
        }
    }

    private static String hexDump(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < offset + length && i < data.length; i++) {
            sb.append(String.format("%02X ", data[i]));
        }
        return sb.toString().trim();
    }
    
    /**
     * Convert an OWL input stream to RDF/XML and write to output stream
     * Useful for in-memory conversion without file I/O
     * 
     * @param inputStream Input stream containing OWL data in any format
     * @param outputStream Output stream to write RDF/XML data
     * @throws OWLOntologyCreationException if input cannot be parsed
     * @throws OWLOntologyStorageException if conversion fails
     */
    public static void convertToRDFXML(InputStream inputStream, OutputStream outputStream)
            throws OWLOntologyCreationException, OWLOntologyStorageException {
        
        log.info("Converting OWL stream to RDF/XML");
        
        // Create OWL API manager
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        
        // Load ontology from stream
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(inputStream);
        log.info("Loaded ontology. Axioms: {}", ontology.getAxiomCount());
        
        // Get original format info
        OWLDocumentFormat originalFormat = manager.getOntologyFormat(ontology);
        log.info("Source format: {}", 
            originalFormat != null ? originalFormat.getClass().getSimpleName() : "Unknown");
        
        // Configure RDF/XML format
        RDFXMLDocumentFormat rdfXmlFormat = new RDFXMLDocumentFormat();
        
        // Preserve prefixes
        if (originalFormat != null && originalFormat.isPrefixOWLDocumentFormat()) {
            PrefixDocumentFormat prefixFormat = originalFormat.asPrefixOWLDocumentFormat();
            prefixFormat.getPrefixName2PrefixMap().forEach(rdfXmlFormat::setPrefix);
        }
        
        // Save as RDF/XML to output stream
        manager.saveOntology(ontology, rdfXmlFormat, outputStream);
        
        log.info("✓ Converted to RDF/XML successfully");
    }
}
