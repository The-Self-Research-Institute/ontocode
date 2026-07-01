package self.research.ontology.owlEditor.util;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.*;
import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        // SILENT mode still tries to connect before ignoring — so we redirect HTTP/HTTPS IRIs
        // to a nonexistent local path for instant failure (same fix as HierarchySnapshotBuildService).
        manager.getIRIMappers().clear();
        manager.addIRIMapper(iri -> {
            String s = iri.toString();
            if (s.startsWith("http://") || s.startsWith("https://")) {
                return org.semanticweb.owlapi.model.IRI.create(
                        "file:///intentionally-missing-import-" + Math.abs(s.hashCode()));
            }
            return null;
        });

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
        OWLOntology ontology;
        try {
            ontology = CompletableFuture.supplyAsync(() -> {
                try {
                    return manager.loadOntologyFromOntologyDocument(fileToLoad.toFile());
                } catch (OWLOntologyCreationException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }).get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IOException("OWL API timed out after 30s loading " + inputPath.getFileName() + " (likely hung on owl:imports fetch)");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof OWLOntologyCreationException owlEx) throw owlEx;
            throw new IOException("OWL API load failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("OWL API load interrupted");
        }
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
        
        // Save as RDF/XML — run in a dedicated thread with a large stack.
        // OWLAPI's AbstractTranslator recurses into OWLEquivalentClassesAxiom visitors,
        // which causes StackOverflowError on ontologies with complex equivalence chains.
        // A 32 MB per-thread stack is isolated; the rest of the JVM is unaffected.
        long saveStart = System.nanoTime();
        try (FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
            saveOntologyLargeStack(manager, ontology, rdfXmlFormat, fos);
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
     * Serializes an ontology, with a fallback that strips axiom annotations if OWLAPI's
     * AbstractTranslator hits infinite recursion (StackOverflowError).
     *
     * Root cause: AbstractTranslator re-queues annotation axioms whose values form circular
     * IRI/blank-node chains with OWLEquivalentClassesAxiom operands, looping forever.
     * Stripping annotations removes the circular references while preserving all semantic axioms.
     * A 32 MB per-thread stack is used to give genuine deep recursion headroom before the
     * StackOverflowError triggers the fallback.
     */
    private static void saveOntologyLargeStack(OWLOntologyManager manager,
                                               OWLOntology ontology,
                                               OWLDocumentFormat format,
                                               java.io.OutputStream out) throws IOException, OWLOntologyStorageException {
        // First attempt
        Throwable firstError = trySaveOnThread(manager, ontology, format, out);
        if (firstError == null) return;

        if (!(firstError instanceof StackOverflowError)) {
            rethrow(firstError);
            return;
        }

        // StackOverflowError from AbstractTranslator: strip axiom annotations and retry.
        log.warn("OWLAPI RDF serialization hit StackOverflowError (AbstractTranslator cycle) — "
                + "retrying with annotation-stripped ontology copy");
        try {
            OWLOntologyManager freshManager = createManagerWithSilentImports();
            OWLOntology stripped = freshManager.createOntology(ontology.getOntologyID());
            ontology.axioms()
                    .map(ax -> ax.getAxiomWithoutAnnotations())
                    .forEach(ax -> freshManager.addAxiom(stripped, ax));

            Throwable secondError = trySaveOnThread(freshManager, stripped, format, out);
            if (secondError == null) {
                log.info("Serialization succeeded after annotation stripping");
                return;
            }
            log.error("Serialization still failed after annotation stripping: {}", secondError.getMessage());
            rethrow(secondError);
        } catch (OWLOntologyCreationException e) {
            throw new IOException("Failed to create stripped ontology for retry", e);
        }
    }

    private static Throwable trySaveOnThread(OWLOntologyManager manager, OWLOntology ontology,
                                             OWLDocumentFormat format, java.io.OutputStream out) {
        Throwable[] error = {null};
        Thread t = new Thread(null, () -> {
            try {
                manager.saveOntology(ontology, format, out);
            } catch (Throwable e) {
                error[0] = e;
            }
        }, "owlapi-serializer", 32 * 1024 * 1024);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error[0] = e;
        }
        return error[0];
    }

    private static void rethrow(Throwable t) throws IOException, OWLOntologyStorageException {
        if (t instanceof OWLOntologyStorageException ose) throw ose;
        if (t instanceof IOException ioe) throw ioe;
        if (t instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            throw new IOException("OWL serialization interrupted", t);
        }
        throw new IOException("OWL serialization failed: " + t.getMessage(), t);
    }

    /**
     * Strip binary garbage bytes that may be prepended before the actual XML/OWL content.
     * This can happen when the upload pipeline (multipart encoding, gateway proxy) corrupts the file.
     * Returns the original path if the file is clean, or a new stripped temp file path.
     */
    private static Path stripBinaryPrefix(Path filePath) throws IOException {
        long fileSize = Files.size(filePath);

        // Only read first 8KB to check header — NOT the entire file
        byte[] headerBuf;
        try (InputStream fis = Files.newInputStream(filePath)) {
            headerBuf = fis.readNBytes((int) Math.min(8192, fileSize));
        }

        // Log first 16 bytes for diagnostics
        StringBuilder hexDumpSb = new StringBuilder();
        for (int i = 0; i < Math.min(16, headerBuf.length); i++) {
            hexDumpSb.append(String.format("%02X ", headerBuf[i]));
        }
        log.info("File header hex (first 16 bytes): {}", hexDumpSb.toString().trim());

        // Check if file already starts with valid XML
        if (headerBuf.length > 0 && (headerBuf[0] == '<' || headerBuf[0] == '@')) {
            log.info("File starts with '{}', no stripping needed", (char) headerBuf[0]);
            return filePath;
        }

        // Check for UTF-8 BOM (EF BB BF) followed by '<' or '@'
        if (headerBuf.length > 3 && headerBuf[0] == (byte) 0xEF && headerBuf[1] == (byte) 0xBB
                && headerBuf[2] == (byte) 0xBF && (headerBuf[3] == '<' || headerBuf[3] == '@')) {
            log.info("File has UTF-8 BOM, stripping 3 bytes");
            Path stripped = filePath.getParent().resolve("stripped-" + filePath.getFileName());
            try (InputStream in = Files.newInputStream(filePath);
                 OutputStream out = Files.newOutputStream(stripped)) {
                in.skipNBytes(3);
                in.transferTo(out);
            }
            return stripped;
        }

        // Search for <?xml or <Ontology or <rdf:RDF or Turtle markers in the first 1024 bytes
        String header = new String(headerBuf, 0, Math.min(1024, headerBuf.length),
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
                hexDump(headerBuf, 0, Math.min(contentStart, 32)));
            Path stripped = filePath.getParent().resolve("stripped-" + filePath.getFileName());
            try (InputStream in = Files.newInputStream(filePath);
                 OutputStream out = Files.newOutputStream(stripped)) {
                in.skipNBytes(contentStart);
                in.transferTo(out);
            }
            log.info("Wrote stripped file: {} ({} bytes)", stripped.getFileName(), fileSize - contentStart);
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
        long fileSize = Files.size(filePath);
        long fileSizeMB = fileSize / (1024 * 1024);
        boolean isLargeFile = fileSize > 50 * 1024 * 1024; // >50MB

        if (isLargeFile) {
            log.info("[PERFORMANCE] Large file detected ({} MB), using optimized sanitization (skipping OWL API re-serialization)", fileSizeMB);
        }

        Path stripped = stripBinaryPrefix(filePath);
        if (!stripped.equals(filePath)) {
            // Stripped file was created - replace original with it
            Files.move(stripped, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("Sanitized file in place: {}", filePath.getFileName());
        }
        
        if (!isLargeFile) {
            // Full sanitization for small files (<50MB)
            fixMalformedRdfXml(filePath);
            sanitizeNTriplesIRIs(filePath);
            reserializeWithOwlApi(filePath);
        } else {
            // Large files: prefix strip only. Whole-file RDF/XML fixup and OWL API
            // re-serialization load the entire ontology into memory; streaming GraphDB
            // import handles RDF/XML directly, with OWL API fallback on structural errors.
            log.info("[PERFORMANCE] Large file sanitization complete (prefix check only; skipped RDF/XML fixup and OWL API re-serialization)");
        }
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

        // Fix 3: Inject missing common namespace declarations (e.g. xmlns:dc, xmlns:skos)
        // These are required for well-formed XML; their absence causes SAX parser errors like
        // "The prefix 'dc' for element 'dc:title' is not bound".
        content = injectMissingNamespaces(content);

        // Fix 4: Remove control characters from IRI-bearing attributes (rdf:about, rdf:resource, etc.)
        // GraphDB throws InvalidValueException for IRIs containing control chars (0x00-0x1F, 0x7F).
        // This must happen before any upload path (server-side, direct, or chunked).
        content = sanitizeRdfXmlIris(content);

        if (!content.equals(original)) {
            log.info("Fixed malformed RDF/XML structure in: {}", filePath.getFileName());
            Files.writeString(filePath, content);
        }
    }

    /**
     * Sanitize IRIs in RDF/XML attribute values by percent-encoding control characters
     * and other characters that GraphDB rejects (InvalidValueException).
     * Targets: rdf:about, rdf:resource, rdf:datatype, rdf:ID, xml:base, xmlns:* values.
     */
    static String sanitizeRdfXmlIris(String content) {
        // Match IRI-bearing attributes in RDF/XML
        // rdf:about="...", rdf:resource="...", rdf:datatype="...", rdf:ID="..."
        Pattern iriAttrPattern = Pattern.compile(
            "(rdf:about|rdf:resource|rdf:datatype|rdf:ID|xml:base)\\s*=\\s*\"([^\"]*)\"");
        
        Matcher m = iriAttrPattern.matcher(content);
        StringBuilder sb = null;
        int lastEnd = 0;
        int fixCount = 0;
        
        while (m.find()) {
            String attrValue = m.group(2);
            String sanitized = sanitizeIriValue(attrValue);
            if (!sanitized.equals(attrValue)) {
                if (sb == null) {
                    sb = new StringBuilder(content.length() + 256);
                }
                sb.append(content, lastEnd, m.start(2));
                sb.append(sanitized);
                lastEnd = m.end(2);
                fixCount++;
            }
        }
        
        if (sb == null) {
            return content;
        }
        
        sb.append(content, lastEnd, content.length());
        log.info("Sanitized {} IRI attribute values in RDF/XML (control chars / invalid chars)", fixCount);
        return sb.toString();
    }

    /**
     * Percent-encode control characters and other GraphDB-rejected characters in an IRI value.
     */
    private static String sanitizeIriValue(String iri) {
        boolean needsEncoding = false;
        for (int i = 0; i < iri.length(); i++) {
            char c = iri.charAt(i);
            if (c < 0x20 || c == 0x7F || c == ' ' || c == '[' || c == ']'
                    || c == '{' || c == '}' || c == '|' || c == '\\' || c == '^' || c == '`') {
                needsEncoding = true;
                break;
            }
        }
        if (!needsEncoding) return iri;
        
        StringBuilder sb = new StringBuilder(iri.length() + 40);
        for (int i = 0; i < iri.length(); i++) {
            char c = iri.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
            else if (c == ' ') sb.append("%20");
            else if (c == '[') sb.append("%5B");
            else if (c == ']') sb.append("%5D");
            else if (c == '{') sb.append("%7B");
            else if (c == '}') sb.append("%7D");
            else if (c == '|') sb.append("%7C");
            else if (c == '\\') sb.append("%5C");
            else if (c == '^') sb.append("%5E");
            else if (c == '`') sb.append("%60");
            else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Inject missing common namespace declarations into an RDF/XML document.
     * Scans for namespace prefix usage (e.g. {@code dc:title}) and adds the corresponding
     * {@code xmlns:prefix} attribute to the {@code <rdf:RDF>} root element when absent.
     * For unknown prefixes not in the well-known list, logs a warning and skips them
     * so the rest of the namespaces are still injected correctly.
     */
    static String injectMissingNamespaces(String content) {
        if (!content.contains("<rdf:RDF")) {
            return content;
        }

        // Well-known prefix → namespace URI mappings
        Map<String, String> knownNamespaces = new LinkedHashMap<>();
        // W3C core
        knownNamespaces.put("rdf",       "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        knownNamespaces.put("rdfs",      "http://www.w3.org/2000/01/rdf-schema#");
        knownNamespaces.put("owl",       "http://www.w3.org/2002/07/owl#");
        knownNamespaces.put("xsd",       "http://www.w3.org/2001/XMLSchema#");
        knownNamespaces.put("xml",       "http://www.w3.org/XML/1998/namespace");
        // Dublin Core & metadata
        knownNamespaces.put("dc",        "http://purl.org/dc/elements/1.1/");
        knownNamespaces.put("dcterms",   "http://purl.org/dc/terms/");
        knownNamespaces.put("terms",     "http://purl.org/dc/terms/");
        // Bibliographic & scholarly
        knownNamespaces.put("bibo",      "http://purl.org/ontology/bibo/");
        knownNamespaces.put("foaf",      "http://xmlns.com/foaf/0.1/");
        knownNamespaces.put("skos",      "http://www.w3.org/2004/02/skos/core#");
        knownNamespaces.put("prov",      "http://www.w3.org/ns/prov#");
        knownNamespaces.put("schema",    "http://schema.org/");
        knownNamespaces.put("vann",      "http://purl.org/vocab/vann/");
        knownNamespaces.put("cc",        "http://creativecommons.org/ns#");
        knownNamespaces.put("doap",      "http://usefulinc.com/ns/doap#");
        // OBO Foundry / biomedical
        knownNamespaces.put("obo",       "http://purl.obolibrary.org/obo/");
        knownNamespaces.put("oboInOwl",  "http://www.geneontology.org/formats/oboInOwl#");
        // SWRL
        knownNamespaces.put("swrl",      "http://www.w3.org/2003/11/swrl#");
        knownNamespaces.put("swrlb",     "http://www.w3.org/2003/11/swrlb#");
        // Semantic science & other common
        knownNamespaces.put("sio",       "http://semanticscience.org/resource/");
        knownNamespaces.put("sh",        "http://www.w3.org/ns/shacl#");
        knownNamespaces.put("dcat",      "http://www.w3.org/ns/dcat#");
        knownNamespaces.put("void",      "http://rdfs.org/ns/void#");
        knownNamespaces.put("org",       "http://www.w3.org/ns/org#");
        knownNamespaces.put("time",      "http://www.w3.org/2006/time#");
        knownNamespaces.put("geo",       "http://www.opengis.net/ont/geosparql#");
        knownNamespaces.put("ssn",       "http://www.w3.org/ns/ssn/");
        knownNamespaces.put("sosa",      "http://www.w3.org/ns/sosa/");
        knownNamespaces.put("faldo",     "http://biohackathon.org/resource/faldo#");

        // Collect all undeclared prefixes used in the document.
        // Only match prefixes in XML element/attribute positions, NOT in text content.
        // Element names: <prefix:Local or </prefix:Local
        // Attribute names: whitespace prefix:attr=
        Pattern elementPrefixUsage = Pattern.compile("</?([a-zA-Z][a-zA-Z0-9_-]*):[a-zA-Z]");
        Pattern attrPrefixUsage = Pattern.compile("\\s([a-zA-Z][a-zA-Z0-9_-]*):[a-zA-Z][a-zA-Z0-9_-]*\\s*=");
        Map<String, Boolean> usedPrefixes = new LinkedHashMap<>();
        Matcher matcher = elementPrefixUsage.matcher(content);
        while (matcher.find()) {
            String prefix = matcher.group(1);
            if ("xmlns".equals(prefix) || "xml".equals(prefix)) continue;
            usedPrefixes.put(prefix, true);
        }
        matcher = attrPrefixUsage.matcher(content);
        while (matcher.find()) {
            String prefix = matcher.group(1);
            if ("xmlns".equals(prefix) || "xml".equals(prefix)) continue;
            usedPrefixes.put(prefix, true);
        }

        List<String> toInject = new ArrayList<>();
        List<String> unknownPrefixes = new ArrayList<>();

        for (String prefix : usedPrefixes.keySet()) {
            boolean isDeclared = content.contains("xmlns:" + prefix + "=");
            if (isDeclared) continue;

            if (knownNamespaces.containsKey(prefix)) {
                toInject.add(prefix);
            } else {
                unknownPrefixes.add(prefix);
            }
        }

        // ── Dynamic resolution for unknown/custom prefixes ──
        if (!unknownPrefixes.isEmpty()) {
            // 1. Extract xml:base from the document
            String xmlBase = null;
            Matcher xmlBaseMatcher = Pattern.compile("xml:base\\s*=\\s*\"([^\"]+)\"").matcher(content);
            if (xmlBaseMatcher.find()) {
                xmlBase = xmlBaseMatcher.group(1);
            }

            // 2. Extract ontology IRI from <owl:Ontology rdf:about="...">
            String ontologyIri = null;
            Matcher ontologyMatcher = Pattern.compile(
                    "<owl:Ontology\\s+rdf:about\\s*=\\s*\"([^\"]+)\"").matcher(content);
            if (ontologyMatcher.find()) {
                ontologyIri = ontologyMatcher.group(1);
            }

            // 3. Extract default namespace (xmlns="...")
            String defaultNs = null;
            Matcher defaultNsMatcher = Pattern.compile(
                    "<rdf:RDF[^>]*\\sxmlns\\s*=\\s*\"([^\"]+)\"").matcher(content);
            if (defaultNsMatcher.find()) {
                defaultNs = defaultNsMatcher.group(1);
            }

            List<String> resolved = new ArrayList<>();

            for (String prefix : unknownPrefixes) {
                String resolvedUri = null;

                // Strategy A: Find full URIs in rdf:about/resource/datatype that match
                // local names used with this prefix. E.g. pizza:Margherita and
                // rdf:about="http://example.org/pizza#Margherita" → pizza → http://example.org/pizza#
                Set<String> localNames = new LinkedHashSet<>();
                Matcher lnMatcher = Pattern.compile(
                        "(?:<|\\s)" + Pattern.quote(prefix) + ":([a-zA-Z][a-zA-Z0-9_.-]*)").matcher(content);
                while (lnMatcher.find()) {
                    localNames.add(lnMatcher.group(1));
                }

                for (String localName : localNames) {
                    Matcher uriMatcher = Pattern.compile(
                            "(?:rdf:about|rdf:resource|rdf:datatype)\\s*=\\s*\"([^\"]+[#/])"
                                    + Pattern.quote(localName) + "\"").matcher(content);
                    if (uriMatcher.find()) {
                        resolvedUri = uriMatcher.group(1);
                        break;
                    }
                }

                // Strategy B: Use xml:base, ontology IRI, or default namespace as base
                if (resolvedUri == null) {
                    String base = xmlBase != null ? xmlBase
                            : (ontologyIri != null ? ontologyIri : defaultNs);
                    if (base != null) {
                        // Derive namespace: base + prefix fragment
                        if (base.endsWith("#") || base.endsWith("/")) {
                            resolvedUri = base;
                        } else {
                            resolvedUri = base + "#";
                        }
                    }
                }

                if (resolvedUri != null) {
                    knownNamespaces.put(prefix, resolvedUri);
                    toInject.add(prefix);
                    resolved.add(prefix);
                    log.info("Dynamically resolved custom namespace prefix '{}' → '{}'", prefix, resolvedUri);
                }
            }

            // Remove resolved ones from unknown list
            unknownPrefixes.removeAll(resolved);

            if (!unknownPrefixes.isEmpty()) {
                log.warn("Undeclared namespace prefixes could not be resolved (no matching URIs, " +
                         "xml:base, or ontology IRI found — these may cause SAX parser errors): {}",
                         unknownPrefixes);
            }
        }

        if (toInject.isEmpty()) {
            return content;
        }

        log.info("Injecting missing XML namespace declarations: {}", toInject);

        int rdfTagStart = content.indexOf("<rdf:RDF");
        int rdfTagEnd   = content.indexOf('>', rdfTagStart);
        if (rdfTagStart < 0 || rdfTagEnd < 0) {
            return content;
        }

        // Insert before the closing '>' (or '/>' for self-closing tags)
        boolean selfClosing = content.charAt(rdfTagEnd - 1) == '/';
        int insertPos = selfClosing ? rdfTagEnd - 1 : rdfTagEnd;

        StringBuilder injection = new StringBuilder();
        for (String prefix : toInject) {
            injection.append("\n         xmlns:").append(prefix)
                     .append("=\"").append(knownNamespaces.get(prefix)).append("\"");
        }

        return content.substring(0, insertPos) + injection + content.substring(insertPos);
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
        
        // Log any owl:imports declarations — these are the network-fetch candidates that can cause hangs
        java.util.regex.Matcher importMatcher = java.util.regex.Pattern
                .compile("owl:imports[^>]*rdf:resource=\"([^\"]+)\"")
                .matcher(content);
        java.util.List<String> imports = new java.util.ArrayList<>();
        while (importMatcher.find()) imports.add(importMatcher.group(1));
        if (imports.isEmpty()) {
            log.info("Re-serializing {} with OWL API — no owl:imports declared", filePath.getFileName());
        } else {
            log.warn("Re-serializing {} with OWL API — {} owl:imports found (these may trigger network calls): {}",
                    filePath.getFileName(), imports.size(), imports);
        }

        try {
            OWLOntologyManager manager = createManagerWithSilentImports();
            OWLOntology ontology;
            try {
                ontology = CompletableFuture.supplyAsync(() -> {
                    try {
                        return manager.loadOntologyFromOntologyDocument(filePath.toFile());
                    } catch (OWLOntologyCreationException e) {
                        throw new java.util.concurrent.CompletionException(e);
                    }
                }).get(30, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.warn("OWL API load timed out after 30s for {} (likely hung on owl:imports fetch) — skipping re-serialization", filePath.getFileName());
                return;
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
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
            
            // Write to a temp file first — NOT directly to filePath.
            // Files.newOutputStream(filePath) truncates the file to 0 bytes before any writing happens.
            // If saveOntology() then throws StackOverflowError, the original file is already destroyed.
            // Writing to a temp file and atomically moving it only on success prevents this data loss.
            Path tempOut = filePath.resolveSibling("reserialized-" + filePath.getFileName());
            try (OutputStream out = Files.newOutputStream(tempOut)) {
                saveOntologyLargeStack(manager, ontology, rdfXmlFormat, out);
            }
            Files.move(tempOut, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            log.info("Successfully re-serialized file as clean RDF/XML: {}", filePath.getFileName());

        } catch (Throwable t) {
            // Catch Throwable (not just Exception) because OWL API's RDF/XML serializer can throw
            // StackOverflowError on deeply nested ontologies — StackOverflowError extends Error, not Exception,
            // so a plain catch(Exception) misses it and kills the worker thread silently.
            log.warn("OWL API re-serialization failed ({}) for {} — skipping, original file will be used: {}",
                    t.getClass().getSimpleName(), filePath.getFileName(), t.getMessage());
            // Clean up temp file if the write started before the error
            try { Files.deleteIfExists(filePath.resolveSibling("reserialized-" + filePath.getFileName())); }
            catch (Exception ignored) {}
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
    public static void convertToRDFXML1(InputStream inputStream, OutputStream outputStream)
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

    /**
     * Sanitize IRIs in N-Triples and Turtle files by encoding spaces and special characters.
     * This fixes "IRI included an unencoded space" errors during RDF parsing.
     * 
     * Handles:
     * - Spaces: ' ' → '%20'
     * - Brackets: '[', ']', '{', '}' → percent-encoded
     * - Special chars: '|', '\', '^', '`', '(', ')' → percent-encoded
     * - Non-ASCII Unicode characters → UTF-8 percent-encoded
     * 
     * @param filePath Path to N-Triples/Turtle file to sanitize in-place
     * @throws IOException if file I/O fails
     */
    public static void sanitizeNTriplesIRIs(Path filePath) throws IOException {
        long fileSize = Files.size(filePath);
        
        // Quick check: read first 8KB to detect format
        byte[] headerBuf;
        try (InputStream fis = Files.newInputStream(filePath)) {
            headerBuf = fis.readNBytes((int) Math.min(8192, fileSize));
        }
        String headerSample = new String(headerBuf, java.nio.charset.StandardCharsets.ISO_8859_1);
        String lowerHeader = headerSample.toLowerCase();
        boolean isNTriples = headerSample.matches("(?s).*<https?://[^>]+>\\s+<[^>]+>\\s+.*");
        boolean isTurtle = lowerHeader.contains("@prefix") || lowerHeader.contains("@base");
        
        if (!isNTriples && !isTurtle) {
            log.info("File does not look like N-Triples/Turtle, skipping IRI sanitization");
            return;
        }
        
        log.info("Sanitizing IRIs in N-Triples/Turtle file: {} ({} MB)", filePath.getFileName(), fileSize / (1024 * 1024));
        
        // Stream-based processing: read line by line, write to temp file
        Path tempFile = filePath.getParent().resolve("sanitized-" + filePath.getFileName());
        int fixedCount = 0;
        
        try (BufferedReader reader = Files.newBufferedReader(filePath, java.nio.charset.StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(tempFile, java.nio.charset.StandardCharsets.UTF_8)) {
            
            String line;
            while ((line = reader.readLine()) != null) {
                // Skip comment lines
                if (line.isEmpty() || line.charAt(0) == '#') {
                    writer.write(line);
                    writer.newLine();
                    continue;
                }
                
                // Fast check: does this line contain any problematic chars inside < >?
                boolean needsProcessing = false;
                boolean inIri = false;
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    if (c == '<') inIri = true;
                    else if (c == '>') inIri = false;
                    else if (inIri && (c < 0x20 || c == 0x7F
                            || c == ' ' || c == '[' || c == ']' || c == '{' || c == '}'
                            || c == '|' || c == '\\' || c == '^' || c == '`'
                            || c == '(' || c == ')' || c > 127)) {
                        needsProcessing = true;
                        break;
                    }
                }
                
                if (!needsProcessing) {
                    writer.write(line);
                    writer.newLine();
                    continue;
                }
                
                // Process IRIs on this line
                StringBuilder processedLine = new StringBuilder(line.length() + 128);
                int pos = 0;
                
                while (pos < line.length()) {
                    int iriStart = line.indexOf('<', pos);
                    if (iriStart < 0) {
                        processedLine.append(line.substring(pos));
                        break;
                    }
                    
                    int iriEnd = line.indexOf('>', iriStart + 1);
                    if (iriEnd < 0) {
                        processedLine.append(line.substring(pos));
                        break;
                    }
                    
                    processedLine.append(line, pos, iriStart + 1);
                    
                    String iri = line.substring(iriStart + 1, iriEnd);
                    String sanitizedIri = sanitizeIri(iri);
                    
                    if (!sanitizedIri.equals(iri)) {
                        fixedCount++;
                    }
                    
                    processedLine.append(sanitizedIri);
                    processedLine.append('>');
                    
                    pos = iriEnd + 1;
                }
                
                writer.write(processedLine.toString());
                writer.newLine();
            }
        } catch (java.nio.charset.MalformedInputException e) {
            // Non-UTF-8 file — skip IRI sanitization (RDF/XML files are not N-Triples)
            log.info("File contains non-UTF-8 bytes, skipping IRI sanitization");
            Files.deleteIfExists(tempFile);
            return;
        }
        
        if (fixedCount > 0) {
            Files.move(tempFile, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("✓ Sanitized {} IRIs with encoding issues in: {}", fixedCount, filePath.getFileName());
        } else {
            Files.deleteIfExists(tempFile);
            log.info("No IRI encoding issues found in: {}", filePath.getFileName());
        }
    }
    
    /**
     * Sanitize a single IRI by percent-encoding problematic characters.
     * 
     * @param iri IRI string (without angle brackets)
     * @return Sanitized IRI with special characters percent-encoded
     */
    private static String sanitizeIri(String iri) {
        if (iri == null || iri.isEmpty()) {
            return iri;
        }
        
        // Fast check: scan for any character that needs encoding
        boolean needsEncoding = false;
        for (int i = 0; i < iri.length(); i++) {
            char c = iri.charAt(i);
            if (c < 0x20 || c == 0x7F
                    || c == '[' || c == ']' || c == '{' || c == '}' || c == '|'
                    || c == '\\' || c == '^' || c == '`' || c == ' '
                    || c == '(' || c == ')' || c > 127) {
                needsEncoding = true;
                break;
            }
        }
        
        if (!needsEncoding) {
            return iri;
        }
        
        // Encode problematic characters
        StringBuilder sb = new StringBuilder(iri.length() + 40);
        for (int i = 0; i < iri.length(); i++) {
            char c = iri.charAt(i);
            // Control characters (0x00-0x1F, 0x7F) — GraphDB rejects these
            if (c < 0x20 || c == 0x7F) {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
            else if (c == '[') sb.append("%5B");
            else if (c == ']') sb.append("%5D");
            else if (c == '{') sb.append("%7B");
            else if (c == '}') sb.append("%7D");
            else if (c == '|') sb.append("%7C");
            else if (c == '\\') sb.append("%5C");
            else if (c == '^') sb.append("%5E");
            else if (c == '`') sb.append("%60");
            else if (c == ' ') sb.append("%20");
            else if (c == '(') sb.append("%28");
            else if (c == ')') sb.append("%29");
            else if (c > 127) {
                // Percent-encode non-ASCII (Unicode) characters
                byte[] utf8 = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for (byte b : utf8) {
                    sb.append('%');
                    sb.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
                    sb.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
            }
            else {
                sb.append(c);
            }
        }
        
        return sb.toString();
    }
}
