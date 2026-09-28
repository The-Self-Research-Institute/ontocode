package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.ManchesterSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.OBODocumentFormat;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

final class OntologyExporter {

    private static final Logger log = LoggerFactory.getLogger(OntologyExporter.class);

    private final SparqlDatasetService datasetService;
    private final Function<String, Path> projectDirs;
    private final CitationPositions citations;

    OntologyExporter(SparqlDatasetService datasetService, Function<String, Path> projectDirs, CitationPositions citations) {
        this.datasetService = datasetService;
        this.projectDirs = projectDirs;
        this.citations = citations;
    }

    public void writeRestoreSnapshot(String projectId, Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            datasetService.exportDatasetToStream(projectId, org.eclipse.rdf4j.rio.RDFFormat.NTRIPLES, out);
        }
    }

    public Path exportOntology(String projectId, String format) throws IOException {
        log.info("Exporting ontology from GraphDB for project: {}", projectId);
        if (requiresOwlApiFormat(format)) {
            return exportOntologyWithOwlApi(projectId, format);
        }

        RDFFormat rdfFormat = resolveLang(format);
        String extension = extensionFor(format);
        Path exportPath = projectDirs.apply(projectId).resolve("ontology.original." + extension);
        Files.createDirectories(exportPath.getParent());
        String content = datasetService.exportDataset(projectId, rdfFormat);
        
        Map<String, String> citationMappings = citations.getCitationEntityMappings(projectId);
        if (!citationMappings.isEmpty()) {
            log.info("Applying smart citation repositioning for {} citations", citationMappings.size());
            content = citations.repositionCitations(content, citationMappings, format);
        }
        
        Files.writeString(exportPath, content);
        log.info("Exported ontology to: {}", exportPath);
        return exportPath;
    }

    public Path exportOntologyForJob(String projectId, String format) throws IOException {
        if (requiresOwlApiFormat(format)) {
            return exportOntologyWithOwlApi(projectId, format);
        }
        RDFFormat rdfFormat = resolveLang(format);
        boolean needsBufferedPath = !citations.getCitationEntityMappings(projectId).isEmpty();
        if (needsBufferedPath) {
            return exportOntology(projectId, format);
        }

        String extension = extensionFor(format);
        Path exportPath = projectDirs.apply(projectId).resolve("ontology.original." + extension);
        Files.createDirectories(exportPath.getParent());
        try (OutputStream out = Files.newOutputStream(exportPath)) {
            datasetService.exportDatasetToStream(projectId, rdfFormat, out);
        }
        log.info("Exported ontology (streamed) to: {} ({} bytes)", exportPath, Files.size(exportPath));
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

    static String extensionFor(String format) {
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
            case "obo" -> "obo";
            default -> format.toLowerCase();
        };
    }

    static boolean requiresOwlApiFormat(String format) {
        if (format == null) {
            return false;
        }
        String normalized = format.toLowerCase();
        return normalized.equals("owlxml")
                || normalized.equals("manchester")
                || normalized.equals("manchestersyntax")
                || normalized.equals("functional")
                || normalized.equals("functionalsyntax")
                || normalized.equals("obo");
    }

    private Path exportOntologyWithOwlApi(String projectId, String format) throws IOException {
        String extension = extensionFor(format);
        Path exportPath = projectDirs.apply(projectId).resolve("ontology.original." + extension);
        Files.createDirectories(exportPath.getParent());

        String rdfXmlContent = datasetService.exportDataset(projectId, RDFFormat.RDFXML);
        if (rdfXmlContent == null || rdfXmlContent.isBlank()) {
            throw new IOException("No RDF/XML content exported from GraphDB for project: " + projectId);
        }
        log.info("Exported {} bytes of RDF/XML from GraphDB for OWL API conversion to {}", rdfXmlContent.length(), format);

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntologyLoaderConfiguration loaderConfig = new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);
        manager.setOntologyLoaderConfiguration(loaderConfig);
        OWLOntology ontology;
        try (ByteArrayInputStream input = new ByteArrayInputStream(rdfXmlContent.getBytes(StandardCharsets.UTF_8))) {
            ontology = manager.loadOntologyFromOntologyDocument(input);
        } catch (OWLOntologyCreationException e) {
            log.error("OWL API failed to parse RDF/XML for project {}: {}", projectId, e.getMessage());
            throw new IOException("Failed to parse ontology for export: " + projectId + " — " + e.getMessage(), e);
        }

        OWLDocumentFormat sourceFormat = manager.getOntologyFormat(ontology);
        OWLDocumentFormat documentFormat = resolveOwlApiFormat(format);

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

    public String convertRdfXmlToOwlApiFormat(String rdfXmlContent, String format) throws IOException {
        if (rdfXmlContent == null || rdfXmlContent.isBlank()) {
            throw new IOException("No RDF/XML content to convert");
        }
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.setOntologyLoaderConfiguration(new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
        OWLOntology ontology;
        try (ByteArrayInputStream input = new ByteArrayInputStream(rdfXmlContent.getBytes(StandardCharsets.UTF_8))) {
            ontology = manager.loadOntologyFromOntologyDocument(input);
        } catch (OWLOntologyCreationException e) {
            throw new IOException("Failed to parse ontology for conversion to " + format + ": " + e.getMessage(), e);
        }
        OWLDocumentFormat sourceFormat = manager.getOntologyFormat(ontology);
        OWLDocumentFormat documentFormat = resolveOwlApiFormat(format);
        if (sourceFormat != null && sourceFormat.isPrefixOWLDocumentFormat()
                && documentFormat.isPrefixOWLDocumentFormat()) {
            var targetPrefixes = documentFormat.asPrefixOWLDocumentFormat();
            sourceFormat.asPrefixOWLDocumentFormat().getPrefixName2PrefixMap().forEach(targetPrefixes::setPrefix);
        }
        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        try {
            manager.saveOntology(ontology, documentFormat, outputStream);
        } catch (OWLOntologyStorageException e) {
            throw new IOException("Failed to convert ontology to format: " + format + " — " + e.getMessage(), e);
        }
        return outputStream.toString(StandardCharsets.UTF_8);
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
            case "obo" -> new OBODocumentFormat();
            case "rdfxml", "rdf", "xml" -> new RDFXMLDocumentFormat();
            default -> new RDFXMLDocumentFormat();
        };
    }
}
