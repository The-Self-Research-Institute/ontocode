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
        if (content.length > 0 && content[0] == '<') {
            log.info("File starts with '<', no stripping needed");
            return filePath;
        }

        // Check for UTF-8 BOM (EF BB BF) followed by '<'
        if (content.length > 3 && content[0] == (byte) 0xEF && content[1] == (byte) 0xBB
                && content[2] == (byte) 0xBF && content[3] == '<') {
            log.info("File has UTF-8 BOM, stripping 3 bytes");
            Path stripped = filePath.getParent().resolve("stripped-" + filePath.getFileName());
            Files.write(stripped, Arrays.copyOfRange(content, 3, content.length));
            return stripped;
        }

        // Search for <?xml or <Ontology or <rdf:RDF marker in the first 1024 bytes
        String header = new String(content, 0, Math.min(1024, content.length),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        int xmlStart = header.indexOf("<?xml");
        if (xmlStart < 0) {
            xmlStart = header.indexOf("<Ontology");
        }
        if (xmlStart < 0) {
            xmlStart = header.indexOf("<rdf:RDF");
        }

        if (xmlStart > 0) {
            log.warn("Found {} bytes of binary garbage before XML content. Stripping prefix.", xmlStart);
            log.info("Garbage bytes: {}",
                hexDump(content, 0, Math.min(xmlStart, 32)));
            Path stripped = filePath.getParent().resolve("stripped-" + filePath.getFileName());
            Files.write(stripped, Arrays.copyOfRange(content, xmlStart, content.length));
            log.info("Wrote stripped file: {} ({} bytes)", stripped.getFileName(), content.length - xmlStart);
            return stripped;
        }

        // No XML marker found - return original and let OWL API try to parse
        log.info("No binary prefix detected or file is not XML format");
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
