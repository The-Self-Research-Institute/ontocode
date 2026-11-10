package self.research.ontology.owlEditor.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.Rio;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OntologyHeaderSurvivesSanitizeTest {

    private static final String EXPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:owl="http://www.w3.org/2002/07/owl#"
                     xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
                     xmlns:dc="http://purl.org/dc/elements/1.1/"
                     xmlns:xsd="http://www.w3.org/2001/XMLSchema#">
              <owl:Ontology rdf:about="http://example.org/onto">
                <owl:versionIRI rdf:resource="http://example.org/onto/1.0"/>
                <dc:title>Circulatory system</dc:title>
                <dc:creator>Test Author</dc:creator>
                <rdfs:comment xml:lang="en">An ontology header comment</rdfs:comment>
              </owl:Ontology>
              <owl:Class rdf:about="http://example.org/onto#Measurement"/>
            </rdf:RDF>
            """;

    private static OWLOntology load(Path file) throws Exception {
        OWLOntologyManager manager = OWLManager.createConcurrentOWLOntologyManager();
        manager.setOntologyLoaderConfiguration(new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                .setLoadAnnotationAxioms(true));
        return manager.loadOntologyFromOntologyDocument(file.toFile());
    }

    private static List<String> annotationValues(OWLOntology ontology) {
        return ontology.annotations().map(OWLAnnotation::getValue).map(Object::toString).collect(Collectors.toList());
    }

    @Test
    void theOntologyIriVersionIriAndAnnotationsAreReadFromTheExportAsIs(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("export.owl");
        Files.writeString(file, EXPORT, StandardCharsets.UTF_8);

        OWLOntology ontology = load(file);

        assertEquals("http://example.org/onto", ontology.getOntologyID().getOntologyIRI().get().toString());
        assertEquals("http://example.org/onto/1.0", ontology.getOntologyID().getVersionIRI().get().toString());
        assertEquals(3, ontology.annotations().count());
    }

    @Test
    void theUploadSanitizeStepKeepsTheOntologyIriVersionIriAndAnnotations(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("export.owl");
        Files.writeString(file, EXPORT, StandardCharsets.UTF_8);

        OWLFormatConverter.sanitizeFileOnDisk(file);
        OWLOntology ontology = load(file);

        assertEquals("http://example.org/onto", ontology.getOntologyID().getOntologyIRI().get().toString());
        assertEquals("http://example.org/onto/1.0", ontology.getOntologyID().getVersionIRI().get().toString());
        List<String> values = annotationValues(ontology);
        assertEquals(3, values.size(), values.toString());
        assertTrue(values.stream().anyMatch(v -> v.contains("Circulatory system")), values.toString());
    }

    @Test
    void anExportWrittenByTheRdf4jRdfXmlWriterKeepsItsHeaderThroughUploadSanitize(@TempDir Path dir) throws Exception {
        ValueFactory vf = SimpleValueFactory.getInstance();
        IRI ont = vf.createIRI("http://example.org/onto");
        Path file = dir.resolve("web-export.owl");
        try (OutputStream out = Files.newOutputStream(file)) {
            RDFWriter w = Rio.createWriter(RDFFormat.RDFXML, out);
            w.startRDF();
            w.handleNamespace("dc", "http://purl.org/dc/elements/1.1/");
            w.handleStatement(vf.createStatement(ont, RDF.TYPE, OWL.ONTOLOGY));
            w.handleStatement(vf.createStatement(ont, OWL.VERSIONIRI, vf.createIRI("http://example.org/onto/1.0")));
            w.handleStatement(vf.createStatement(ont, vf.createIRI("http://purl.org/dc/elements/1.1/title"), vf.createLiteral("Circulatory system")));
            w.handleStatement(vf.createStatement(ont, vf.createIRI("http://purl.org/dc/elements/1.1/creator"), vf.createLiteral("Test Author", "en")));
            w.handleStatement(vf.createStatement(ont, RDFS.COMMENT, vf.createLiteral("An ontology header comment")));
            w.handleStatement(vf.createStatement(vf.createIRI("http://example.org/onto#Measurement"), RDF.TYPE, OWL.CLASS));
            w.endRDF();
        }

        OWLFormatConverter.sanitizeFileOnDisk(file);
        OWLOntology ontology = load(file);

        assertEquals("http://example.org/onto", ontology.getOntologyID().getOntologyIRI().get().toString());
        assertEquals("http://example.org/onto/1.0", ontology.getOntologyID().getVersionIRI().get().toString());
        assertEquals(3, ontology.annotations().count(), annotationValues(ontology).toString());
    }
}
