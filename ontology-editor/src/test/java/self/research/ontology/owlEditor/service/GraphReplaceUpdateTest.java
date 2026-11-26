package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphReplaceUpdateTest {

    private static final String MAIN = "urn:graph:main";
    private static final String DRAFT = "urn:graph:draft:u1";
    private static final String OTHER_DRAFT = "urn:graph:draft:u2";
    private static final String MERGED = "<urn:new> <urn:p> \"new\" .\n_:b <urn:p> <urn:new> .\n";

    private SailRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SailRepository(new MemoryStore());
        repository.init();
        execute("INSERT DATA { GRAPH <" + MAIN + "> { <urn:old> <urn:p> \"old\" } "
                + "GRAPH <" + DRAFT + "> { <urn:d> <urn:p> \"draft\" } "
                + "GRAPH <" + OTHER_DRAFT + "> { <urn:o> <urn:p> \"other\" } }");
    }

    @AfterEach
    void tearDown() {
        repository.shutDown();
    }

    @Test
    void oneRequestReplacesPublicAndClearsOnlyThePublishersDraft() {
        GraphReplaceUpdate update = GraphReplaceUpdate.of(MAIN, MERGED, RDFFormat.TURTLE, List.of(DRAFT));

        execute(update.text());

        assertEquals(2, update.triples());
        assertEquals(2, size(MAIN));
        assertTrue(has(MAIN, "urn:new"));
        assertFalse(has(MAIN, "urn:old"));
        assertEquals(0, size(DRAFT));
        assertEquals(1, size(OTHER_DRAFT));
    }

    @Test
    void aRequestThatFailsPartwayChangesNothing() {
        String failing = GraphReplaceUpdate.of(MAIN, MERGED, RDFFormat.TURTLE, List.of(DRAFT)).text()
                + ";\nLOAD <file:///ontocode/missing/file.ttl> INTO GRAPH <urn:graph:elsewhere>";

        assertThrows(Exception.class, () -> execute(failing));

        assertEquals(1, size(MAIN));
        assertTrue(has(MAIN, "urn:old"));
        assertEquals(1, size(DRAFT));
    }

    @Test
    void aPlainReplaceLeavesEveryOtherGraphAlone() {
        execute(GraphReplaceUpdate.of(MAIN, MERGED, RDFFormat.TURTLE, List.of()).text());

        assertEquals(2, size(MAIN));
        assertEquals(1, size(DRAFT));
        assertEquals(1, size(OTHER_DRAFT));
    }

    @Test
    void emptyContentIsRefusedBeforeAnythingIsSent() {
        assertThrows(IllegalArgumentException.class,
                () -> GraphReplaceUpdate.of(MAIN, " ", RDFFormat.TURTLE, List.of(DRAFT)));
    }

    private void execute(String update) {
        try (RepositoryConnection conn = repository.getConnection()) {
            conn.prepareUpdate(update).execute();
        }
    }

    private long size(String graph) {
        try (RepositoryConnection conn = repository.getConnection()) {
            return conn.size(iri(graph));
        }
    }

    private boolean has(String graph, String subject) {
        try (RepositoryConnection conn = repository.getConnection()) {
            return conn.hasStatement(iri(subject), null, null, false, iri(graph));
        }
    }

    private static IRI iri(String value) {
        return SimpleValueFactory.getInstance().createIRI(value);
    }
}
