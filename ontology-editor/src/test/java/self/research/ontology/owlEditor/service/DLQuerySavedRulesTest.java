package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class DLQuerySavedRulesTest {

    private static final String TURTLE = "@prefix : <" + SavedRulesFixtures.NS + "> .\n"
            + "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
            + ":Person a owl:Class .\n"
            + ":Adult a owl:Class .\n"
            + ":john a owl:NamedIndividual, :Person .\n";

    private DLQueryService service(SavedRulesService saved) {
        SparqlDatasetService datasets = mock(SparqlDatasetService.class);
        doAnswer(inv -> {
            OutputStream out = inv.getArgument(2);
            out.write(TURTLE.getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(datasets).exportDatasetToStream(anyString(), any(RDFFormat.class), any(OutputStream.class));
        DLQueryService dl = new DLQueryService(mock(GridFsTemplate.class), datasets, 5_000_000L, mock(ProjectImportService.class));
        dl.useSavedRules(saved);
        return dl;
    }

    @SuppressWarnings("unchecked")
    private static List<String> instanceIris(Map<String, Object> response) {
        Map<String, Object> results = (Map<String, Object>) response.get("results");
        return ((List<Map<String, Object>>) results.get("instances")).stream().map(i -> (String) i.get("iri")).toList();
    }

    @Test
    void aSavedRuleConcludesNewInstancesInADlQuery() throws Exception {
        Map<String, Object> response = service(SavedRulesFixtures.withRule()).executeQuery("p1", "Adult", List.of("instances"));

        assertEquals(List.of(SavedRulesFixtures.NS + "john"), instanceIris(response));
        assertEquals("complete", response.get("completion"));
    }

    @Test
    void withoutSavedRulesTheQueryBehavesAsBefore() throws Exception {
        Map<String, Object> response = service(SavedRulesFixtures.withoutRules()).executeQuery("p1", "Adult", List.of("instances"));

        assertTrue(instanceIris(response).isEmpty());
        assertEquals("complete", response.get("completion"));
        assertNull(response.get("downgradedWarning"));
    }

    @Test
    void anUnreachableRuleServiceMakesTheAnswerPartialWithANote() throws Exception {
        Map<String, Object> response = service(SavedRulesFixtures.unreachable()).executeQuery("p1", "Adult", List.of("instances"));

        assertEquals("partial", response.get("completion"));
        assertEquals(SavedRulesService.UNAVAILABLE_NOTE, response.get("downgradedWarning"));
        assertFalse(instanceIris(response).contains(SavedRulesFixtures.NS + "john"));
    }
}
