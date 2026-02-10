package self.research.ontology.owlEditor.util;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.*;
import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

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
        
        // Create OWL API manager with silent import handling
        OWLOntologyManager manager = createManagerWithSilentImports();
        
        // Load ontology (OWL API will auto-detect format)
        long loadStart = System.nanoTime();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(inputPath.toFile());
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
        
        return outputPath;
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
