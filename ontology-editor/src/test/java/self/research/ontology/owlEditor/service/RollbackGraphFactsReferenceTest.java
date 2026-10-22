package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RollbackGraphFactsReferenceTest {

    private static final String P = "proj";
    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String PIZZA = "http://x/GlutenFreePizza";
    private static final String INDIVIDUAL = "http://x/GlutenFreePizza1";
    private static final String OTHER = "http://x/Other";

    private SailRepository repository;
    private RollbackGraphFacts facts;

    @BeforeEach
    void setUp() {
        repository = new SailRepository(new MemoryStore());
        repository.init();
        SparqlDatasetService datasets = mock(SparqlDatasetService.class);
        when(datasets.execAsk(org.mockito.ArgumentMatchers.eq(P), anyString())).thenAnswer(call -> {
            try (RepositoryConnection connection = repository.getConnection()) {
                return connection.prepareBooleanQuery((String) call.getArgument(1)).evaluate();
            }
        });
        facts = new RollbackGraphFacts(datasets, null, null);
    }

    @AfterEach
    void tearDown() {
        repository.shutDown();
    }

    private void add(String s, String p, String o) {
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(VF.createIRI(s), VF.createIRI(p), VF.createIRI(o));
        }
    }

    private static Model inserted(String s, String p, String o) {
        Model model = new LinkedHashModel();
        model.add(VF.createIRI(s), VF.createIRI(p), VF.createIRI(o));
        return model;
    }

    @Test
    void ignoresReferencesThatTheSameChangeSetInserted() {
        add(INDIVIDUAL, RDF.TYPE.stringValue(), PIZZA);
        Model own = inserted(INDIVIDUAL, RDF.TYPE.stringValue(), PIZZA);
        assertFalse(facts.referencedOutside(P, PIZZA, false, null, own));
    }

    @Test
    void stillRefusesWhenSomeoneElseReferencesIt() {
        add(INDIVIDUAL, RDF.TYPE.stringValue(), PIZZA);
        add(OTHER, RDF.TYPE.stringValue(), PIZZA);
        Model own = inserted(INDIVIDUAL, RDF.TYPE.stringValue(), PIZZA);
        assertTrue(facts.referencedOutside(P, PIZZA, false, null, own));
    }

    @Test
    void anyReferenceCountsWhenTheChangeSetInsertedNone() {
        add(INDIVIDUAL, RDF.TYPE.stringValue(), PIZZA);
        assertTrue(facts.referencedOutside(P, PIZZA, false, null, new LinkedHashModel()));
    }
}
