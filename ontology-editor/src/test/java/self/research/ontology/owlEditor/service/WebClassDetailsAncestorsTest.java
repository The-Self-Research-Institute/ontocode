package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryResults;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class WebClassDetailsAncestorsTest {

    private static final String NS = "http://example.org/anc#";

    private static final String TTL = """
            @prefix : <http://example.org/anc#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            :hasTopping a owl:ObjectProperty ; rdfs:label "hasTopping" .
            :hasSpice a owl:ObjectProperty ; rdfs:label "hasSpice" .
            :Cheese a owl:Class ; rdfs:label "Cheese" .
            :Pepper a owl:Class ; rdfs:label "Pepper" .
            :Pizza a owl:Class ; rdfs:label "Pizza" .
            :CheesyPizza a owl:Class ; rdfs:label "CheesyPizza" ;
              rdfs:subClassOf :Pizza , owl:Thing , [ a owl:Restriction ; owl:onProperty :hasTopping ; owl:someValuesFrom :Cheese ] .
            :Margherita a owl:Class ; rdfs:label "Margherita" ; rdfs:subClassOf :CheesyPizza .
            :PepperPizza a owl:Class ; rdfs:label "PepperPizza" ;
              owl:equivalentClass [ a owl:Class ; owl:intersectionOf ( :Pizza [ a owl:Restriction ; owl:onProperty :hasSpice ; owl:someValuesFrom :Pepper ] ) ] .
            :HotPepperPizza a owl:Class ; rdfs:label "HotPepperPizza" ; rdfs:subClassOf :PepperPizza .
            :Own a owl:Class ; rdfs:label "Own" ;
              rdfs:subClassOf [ a owl:Class ; owl:unionOf ( :Cheese :Pepper ) ] .
            """;

    @Mock
    private SparqlDatasetService datasetService;
    @Mock
    private TopLevelClassCacheService topLevelCache;

    private SailRepository repo;
    private OntologyQueryService service;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        repo = new SailRepository(new MemoryStore());
        try (RepositoryConnection c = repo.getConnection()) {
            c.add(new StringReader(TTL), "", RDFFormat.TURTLE);
        }
        when(datasetService.execSelect(eq("p"), anyString())).thenAnswer(inv -> select(inv.getArgument(1)));
        service = new OntologyQueryService(datasetService, topLevelCache);
    }

    @AfterEach
    void tearDown() {
        repo.shutDown();
    }

    private TupleQueryResult select(String query) {
        try (RepositoryConnection c = repo.getConnection(); TupleQueryResult rows = c.prepareTupleQuery(query).evaluate()) {
            List<String> names = rows.getBindingNames();
            List<BindingSet> all = QueryResults.asList(rows);
            return new SimpleTupleQueryResult(names, all);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> ancestors(String cls) {
        return (List<Map<String, String>>) service.classDetails("p", NS + cls).get("anonymousAncestorAxioms");
    }

    @Test
    void inheritedRestrictionComesFromTheParentAndFillerIsNotAParent() {
        List<Map<String, String>> list = ancestors("Margherita");
        assertTrue(list.stream().anyMatch(e -> e.get("ancestorIri").equals(NS + "CheesyPizza")
                && "false".equals(e.get("navigable")) && "hasTopping some Cheese".equals(e.get("definition"))), list.toString());
        assertTrue(list.stream().anyMatch(e -> e.get("ancestorIri").equals(NS + "CheesyPizza")
                && "true".equals(e.get("navigable")) && e.get("id").equals(NS + "Pizza")), list.toString());
        assertTrue(list.stream().noneMatch(e -> e.get("id").equals(NS + "Cheese")), list.toString());
        assertTrue(list.stream().noneMatch(e -> e.containsKey("fromDefinition")), list.toString());
        assertTrue(list.stream().noneMatch(e -> e.get("id").endsWith("Thing")), list.toString());
    }

    @Test
    void equivalentClassDefinitionOfAnAncestorIsDescribedInFull() {
        List<Map<String, String>> list = ancestors("HotPepperPizza");
        assertEquals(1, list.size(), list.toString());
        assertEquals(NS + "PepperPizza", list.get(0).get("ancestorIri"));
        assertEquals("Pizza and hasSpice some Pepper", list.get(0).get("definition"));
        assertEquals("true", list.get(0).get("fromDefinition"));
    }

    @Test
    void directComplexSuperclassIsListedForTheClassItself() {
        List<Map<String, String>> list = ancestors("Own");
        assertTrue(list.stream().anyMatch(e -> e.get("ancestorIri").equals(NS + "Own")
                && "false".equals(e.get("navigable")) && e.get("definition").contains(" or ")), list.toString());
    }
}
