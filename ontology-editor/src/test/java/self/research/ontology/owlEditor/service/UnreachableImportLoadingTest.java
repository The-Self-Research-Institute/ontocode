package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import self.research.ontology.owlEditor.util.OWLFormatConverter;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class UnreachableImportLoadingTest {

    private static final String UNREACHABLE = "http://10.255.255.1/never-answers.owl";
    private static final Duration LIMIT = Duration.ofSeconds(5);

    private static final String RDF = """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:owl="http://www.w3.org/2002/07/owl#"
                     xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
              <owl:Ontology rdf:about="http://example.org/with-import">
                <owl:imports rdf:resource="%s"/>
              </owl:Ontology>
              <owl:Class rdf:about="http://example.org/with-import#Thing1"/>
            </rdf:RDF>
            """.formatted(UNREACHABLE);

    @Test
    void theSharedManagerSkipsAnImportThatNeverAnswers() {
        OWLOntology loaded = assertTimeoutPreemptively(LIMIT, () ->
                OWLFormatConverter.createManagerWithSilentImports()
                        .loadOntologyFromOntologyDocument(new StringDocumentSource(RDF)));

        assertEquals(1, loaded.classesInSignature().filter(c -> c.getIRI().toString().endsWith("Thing1")).count());
    }

    @Test
    void aConcurrentManagerThatBlocksRemoteImportsSkipsThemToo() {
        OWLOntologyManager manager = OWLManager.createConcurrentOWLOntologyManager();
        manager.setOntologyLoaderConfiguration(manager.getOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(org.semanticweb.owlapi.model.MissingImportHandlingStrategy.SILENT));
        OWLFormatConverter.blockRemoteImports(manager);

        OWLOntology loaded = assertTimeoutPreemptively(LIMIT, () ->
                manager.loadOntologyFromOntologyDocument(new StringDocumentSource(RDF)));

        assertEquals(1, loaded.classesInSignature().filter(c -> c.getIRI().toString().endsWith("Thing1")).count());
    }

    @Test
    void theRuleServiceLoaderSkipsAnImportThatNeverAnswers() {
        OWLOntology loaded = assertTimeoutPreemptively(LIMIT, () -> RuleService.loadTolerant(RDF));

        assertEquals(1, loaded.classesInSignature().filter(c -> c.getIRI().toString().endsWith("Thing1")).count());
    }
}
