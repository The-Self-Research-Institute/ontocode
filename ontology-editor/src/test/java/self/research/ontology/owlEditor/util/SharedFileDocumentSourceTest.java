package self.research.ontology.owlEditor.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedFileDocumentSourceTest {

    private static final String RELATIVE = """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:owl="http://www.w3.org/2002/07/owl#"
                     xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
              <owl:Ontology rdf:about=""/>
              <owl:Class rdf:about="#Dish"/>
              <owl:Class rdf:about="#Pasta">
                <rdfs:subClassOf rdf:resource="#Dish"/>
              </owl:Class>
            </rdf:RDF>
            """;

    @TempDir
    Path dir;

    private static Set<String> classes(OWLOntology ontology) {
        return ontology.classesInSignature().map(OWLClass::getIRI).map(Object::toString).collect(Collectors.toSet());
    }

    @Test
    void relativeNamesResolveExactlyAsTheyDidWithTheOldFileSource() throws Exception {
        Path file = dir.resolve("menu.owl");
        Files.writeString(file, RELATIVE);

        OWLOntology shared = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(SharedFileDocumentSource.of(file));
        OWLOntology old = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new FileDocumentSource(file.toFile()));

        assertEquals(classes(old), classes(shared));
        assertEquals(2, classes(shared).size());
        assertTrue(classes(shared).stream().anyMatch(iri -> iri.endsWith("menu.owl#Dish")), classes(shared).toString());
        assertEquals(old.getAxiomCount(), shared.getAxiomCount());
    }

    @Test
    void aFileBeingReadThroughTheSourceCanStillBeReplaced() throws Exception {
        Path file = dir.resolve("menu.owl");
        Files.writeString(file, RELATIVE);

        try (InputStream reading = SharedFileDocumentSource.of(file).getInputStream().orElseThrow()) {
            AtomicFiles.writeString(file, "replaced");
            assertEquals("replaced", Files.readString(file));
            assertTrue(new String(reading.readAllBytes()).contains("#Pasta"));
        }
    }
}
