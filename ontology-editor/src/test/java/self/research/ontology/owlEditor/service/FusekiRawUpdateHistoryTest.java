package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.service.delta.DeltaSparql;
import self.research.ontology.owlEditor.service.delta.ExactDelta;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "FUSEKI_URL", matches = ".+")
class FusekiRawUpdateHistoryTest {

    private static final String G = "urn:test:raw-update";
    private static final String P = "proj";
    private static final String PREFIXES = "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n"
            + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
            + "PREFIX owl: <http://www.w3.org/2002/07/owl#>\n";

    private Repository repo;
    private SparqlDatasetService datasetService;
    private SparqlDatasetService injector;
    private OntologyHistoryService historyService;
    private RawUpdateHistory rawUpdateHistory;

    private static String base() {
        String url = System.getenv("FUSEKI_URL");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @BeforeEach
    void setUp() throws Exception {
        SPARQLRepository sparql = new SPARQLRepository(base() + "/sparql", base() + "/update");
        sparql.init();
        repo = sparql;
        try (RepositoryConnection c = repo.getConnection()) {
            c.prepareUpdate("DROP SILENT GRAPH <" + G + ">").execute();
        }
        Path file = Path.of(System.getProperty("user.dir")).getParent().resolve("test-data/PizzaTutorialWithDataV2 (1).owl");
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/data?graph=" + URLEncoder.encode(G, StandardCharsets.UTF_8)))
                .header("Content-Type", "application/rdf+xml").POST(HttpRequest.BodyPublishers.ofFile(file)).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() / 100 == 2, response.body());
        datasetService = mock(SparqlDatasetService.class);
        when(datasetService.graphTarget(P)).thenReturn(new SparqlDatasetService.ProjectGraphTarget(repo, "urn:test:main"));
        when(datasetService.getDraftGraphUri(P, "u1")).thenReturn(G);
        injector = Mockito.mock(SparqlDatasetService.class, Mockito.CALLS_REAL_METHODS);
        historyService = mock(OntologyHistoryService.class);
        rawUpdateHistory = new RawUpdateHistory(datasetService);
        ReflectionTestUtils.setField(rawUpdateHistory, "historyService", historyService);
        ReflectionTestUtils.setField(rawUpdateHistory, "draftTrackingService", mock(DraftTrackingService.class));
    }

    @AfterEach
    void tearDown() {
        repo.shutDown();
    }

    private void apply(String update) {
        String body = update.replaceAll("(?m)^\\s*PREFIX\\s+(rdf|rdfs|owl|xsd):.*$", "");
        String injected = ReflectionTestUtils.invokeMethod(injector, "injectGraphContext", PREFIXES + body, G);
        try (RepositoryConnection c = repo.getConnection()) {
            c.prepareUpdate(injected).execute();
        }
    }

    private Model dump() {
        try (RepositoryConnection c = repo.getConnection()) {
            Model model = new LinkedHashModel();
            IRI graph = SimpleValueFactory.getInstance().createIRI(G);
            c.getStatements(null, null, null, false, graph).forEach(s -> model.add(s.getSubject(), s.getPredicate(), s.getObject()));
            return model;
        }
    }

    private String anOwlClass() {
        try (RepositoryConnection c = repo.getConnection();
             TupleQueryResult rows = c.prepareTupleQuery("SELECT ?c WHERE { GRAPH <" + G
                     + "> { ?c a <http://www.w3.org/2002/07/owl#Class> "
                     + "FILTER(isIRI(?c)) } } LIMIT 1").evaluate()) {
            BindingSet row = rows.next();
            return row.getValue("c").stringValue();
        }
    }

    @Test
    void aDraftRawUpdateIsRecordedWithADeltaThatUndoesAndRedoesExactly() {
        String cls = anOwlClass();
        Model before = rawUpdateHistory.snapshot(P, true, "u1");
        assertNotNull(before);
        assertTrue(before.size() > 100);

        apply("DELETE { <" + cls + "> rdfs:label ?l } INSERT { <" + cls + "> rdfs:label \"Renamed in draft\"@en ;"
                + " rdfs:subClassOf [ a owl:Restriction ; owl:onProperty <http://ex/p> ; owl:someValuesFrom owl:Thing ] }"
                + " WHERE { OPTIONAL { <" + cls + "> rdfs:label ?l } }");
        Model changed = dump();
        assertFalse(Models.isomorphic(before, changed));

        rawUpdateHistory.record(P, true, "u1", "User One", before);

        ArgumentCaptor<ChangeOrigin> origins = ArgumentCaptor.forClass(ChangeOrigin.class);
        verify(historyService, atLeastOnce()).recordEdit(anyString(), anyString(), anyString(), anyString(), any(), any(),
                any(), any(), any(), any(), any(), anyBoolean(), origins.capture());
        Map<String, Object> stamped = new HashMap<>();
        List<ChangeOrigin> withExact = origins.getAllValues().stream().filter(o -> o != null && o.exact() != null).toList();
        assertFalse(withExact.isEmpty(), "no recorded entry carries an exact delta");
        withExact.get(0).exact().stamp(stamped, "edit-1");
        ExactDelta delta = ExactDelta.of((String) stamped.get("exactDeleted"), (String) stamped.get("exactInserted"));

        try (RepositoryConnection c = repo.getConnection()) {
            for (String query : DeltaSparql.holds(delta, true, G)) {
                assertTrue(c.prepareBooleanQuery(query).evaluate(), query);
            }
        }
        apply(DeltaSparql.reverse(delta));
        assertTrue(Models.isomorphic(before, dump()), "undo did not restore the draft graph");
        apply(DeltaSparql.forward(delta));
        assertTrue(Models.isomorphic(changed, dump()), "redo did not reapply the change");
    }
}
