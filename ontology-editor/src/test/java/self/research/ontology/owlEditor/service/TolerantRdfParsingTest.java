package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParseException;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.BasicParserSettings;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.service.delta.DeltaCapture;
import self.research.ontology.owlEditor.service.delta.ExactDelta;

import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TolerantRdfParsingTest {

    private static final String BAD = "http://example.org/ontologies/my-hhghgont##dollar";
    private static final String DOC = "<" + BAD + "> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/2002/07/owl#Class> .\n";

    @Test
    void strictParserRejectsDoubleHashButTolerantParserAccepts() throws Exception {
        RDFParser strict = Rio.createParser(RDFFormat.NTRIPLES);
        strict.getParserConfig().set(BasicParserSettings.VERIFY_URI_SYNTAX, true);
        strict.getParserConfig().setNonFatalErrors(new HashSet<>());
        strict.setRDFHandler(new StatementCollector());
        assertThrows(RDFParseException.class, () -> strict.parse(new StringReader(DOC), ""));

        RDFParser tolerant = Rio.createParser(RDFFormat.NTRIPLES);
        StatementCollector collector = new StatementCollector();
        tolerant.setRDFHandler(collector);
        TolerantRdfParsing.apply(tolerant);
        tolerant.parse(new StringReader(DOC), "");
        assertEquals(1, collector.getStatements().size());
        assertEquals(BAD, collector.getStatements().iterator().next().getSubject().stringValue());
    }

    @Test
    void connectionIsConfiguredTolerant() {
        Repository repo = new SailRepository(new MemoryStore());
        repo.init();
        try (RepositoryConnection connection = TolerantRdfParsing.apply(repo.getConnection())) {
            assertFalse(connection.getParserConfig().get(BasicParserSettings.VERIFY_URI_SYNTAX));
            assertTrue(connection.getParserConfig().getNonFatalErrors().contains(BasicParserSettings.VERIFY_URI_SYNTAX));
        } finally {
            repo.shutDown();
        }
    }

    @Test
    void deltaIsCapturedOnGraphContainingDoubleHashIri() {
        Repository repo = new SailRepository(new MemoryStore());
        repo.init();
        try {
            var vf = SimpleValueFactory.getInstance();
            IRI graph = vf.createIRI("urn:test:graph");
            IRI bad = vf.createIRI(BAD);
            IRI fresh = vf.createIRI("http://example.org/ontologies/my-hhghgont#fresh");
            try (RepositoryConnection c = repo.getConnection()) {
                c.add(bad, RDF.TYPE, OWL.CLASS, graph);
            }
            Optional<DeltaCapture> capture = DeltaCapture.begin(repo, graph.stringValue(), List.of(BAD, fresh.stringValue()));
            assertTrue(capture.isPresent());
            try (RepositoryConnection c = repo.getConnection()) {
                c.add(fresh, RDF.TYPE, OWL.CLASS, graph);
            }
            Optional<ExactDelta> delta = capture.get().finish();
            assertTrue(delta.isPresent());
        } finally {
            repo.shutDown();
        }
    }
}
