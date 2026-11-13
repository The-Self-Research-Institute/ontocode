package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.UnloadableImportException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TolerantOntologyLoadingTest {

    private static final String ONTOLOGY_WITH_UNREACHABLE_IMPORT = """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix : <http://example.org/shop#> .
            <http://example.org/shop> a owl:Ontology ;
                owl:imports <http://127.0.0.1:9/never-there.owl> .
            :Customer a owl:Class .
            :alice a :Customer .
            """;

    @Test
    void theDefaultLoaderFailsTheWholeLoadWhenAnImportCannotBeFetched() {
        assertThrows(UnloadableImportException.class, () ->
                OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(
                        new StringDocumentSource(ONTOLOGY_WITH_UNREACHABLE_IMPORT)));
    }

    @Test
    void theTolerantLoaderKeepsTheOntologyWhenAnImportCannotBeFetched() throws Exception {
        OWLOntology ontology = TolerantOntologyLoading.manager().loadOntologyFromOntologyDocument(
                new StringDocumentSource(ONTOLOGY_WITH_UNREACHABLE_IMPORT));

        assertEquals(1, ontology.getIndividualsInSignature().size());
        assertEquals(1, ontology.getClassesInSignature().size());
    }

    @Test
    void theTolerantLoaderStillLoadsAnOntologyWithoutImports() throws Exception {
        OWLOntology ontology = TolerantOntologyLoading.manager().loadOntologyFromOntologyDocument(
                new StringDocumentSource("""
                        @prefix owl: <http://www.w3.org/2002/07/owl#> .
                        @prefix : <http://example.org/plain#> .
                        <http://example.org/plain> a owl:Ontology .
                        :Pizza a owl:Class .
                        """));

        assertEquals(1, ontology.getClassesInSignature().size());
    }
}
