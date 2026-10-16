package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.impl.MapBindingSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OntologyIriUpdateTest {

    private static final String PUBLIC_IRI = "http://ex.org/public";
    private static final String DRAFT_IRI = "http://ex.org/draft";

    @Mock
    private SparqlDatasetService datasetService;
    @Mock
    private ProjectMetadataService projectMetadataService;
    @Mock
    private OntologyMutationService mutationService;
    @Mock
    private GeneralClassAxiomService generalClassAxiomService;
    @Mock
    private ProjectImportService importService;
    @Mock
    private ManchesterExpressionService manchesterExpressionService;
    @Mock
    private StorageManager storageManager;

    private OntologyMetadataService service;
    private Map<String, String> cache;
    private final List<Boolean> scopeSeenByQueries = new java.util.ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new OntologyMetadataService(datasetService, projectMetadataService, mutationService,
                generalClassAxiomService, importService, manchesterExpressionService, storageManager);
        cache = (Map<String, String>) ReflectionTestUtils.getField(service, "ontologyIriCache");
        cache.put("p", PUBLIC_IRI);
        when(projectMetadataService.readMeta("p")).thenReturn(Optional.of(new HashMap<String, Object>()));
        when(datasetService.execSelect(eq("p"), anyString())).thenAnswer(inv -> {
            boolean draftScope = SparqlQueryContext.wantsDraft();
            scopeSeenByQueries.add(draftScope);
            return resultOf(draftScope ? DRAFT_IRI : PUBLIC_IRI);
        });
    }

    @AfterEach
    void tearDown() {
        SparqlQueryContext.clear();
    }

    private static TupleQueryResult resultOf(String iri) {
        MapBindingSet binding = new MapBindingSet();
        binding.addBinding("ont", SimpleValueFactory.getInstance().createIRI(iri));
        TupleQueryResult result = org.mockito.Mockito.mock(TupleQueryResult.class);
        when(result.hasNext()).thenReturn(true, false);
        when(result.next()).thenReturn((BindingSet) binding);
        return result;
    }

    private String capturedUpdate() {
        ArgumentCaptor<String> sparql = ArgumentCaptor.forClass(String.class);
        verify(mutationService).applyRawUpdateWithHistory(eq("p"), sparql.capture(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        return sparql.getValue();
    }

    @Test
    void aDraftIriChangeMovesTheIriThatTheDraftActuallyHolds() {
        service.updateOntologyIRIs("p", "http://ex.org/renamed", "", true, "u1", "User");

        String update = capturedUpdate();
        assertTrue(update.contains("DELETE { <" + DRAFT_IRI + "> ?p ?o }"), update);
        assertFalse(update.contains("<" + PUBLIC_IRI + "> ?p ?o"), update);
        assertEquals(List.of(true), scopeSeenByQueries);
    }

    @Test
    void aDraftIriChangeLeavesThePublicCacheAndMetadataAlone() {
        service.updateOntologyIRIs("p", "http://ex.org/renamed", "", true, "u1", "User");

        assertEquals(PUBLIC_IRI, cache.get("p"));
        verify(projectMetadataService, org.mockito.Mockito.never()).writeMeta(anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void aPublicIriChangeStillUsesTheCachedIriAndUpdatesTheCache() {
        service.updateOntologyIRIs("p", "http://ex.org/renamed", "", false, null, null);

        String update = capturedUpdate();
        assertTrue(update.contains("DELETE { <" + PUBLIC_IRI + "> ?p ?o }"), update);
        assertEquals("http://ex.org/renamed", cache.get("p"));
        assertTrue(scopeSeenByQueries.isEmpty());
    }

    @Test
    void aPublicIriChangeIgnoresAnActiveDraftReadScopeOnTheThread() {
        cache.remove("p");
        SparqlQueryContext.setUserId("u1");
        SparqlQueryContext.setWantsDraft(true);

        service.updateOntologyIRIs("p", "http://ex.org/renamed", "", false, "u1", "User");

        assertEquals(List.of(false), scopeSeenByQueries);
        assertTrue(capturedUpdate().contains("<" + PUBLIC_IRI + "> ?p ?o"));
        assertTrue(SparqlQueryContext.wantsDraft());
    }

    @Test
    void readingMetadataInADraftScopeBypassesThePublicCache() {
        SparqlQueryContext.setUserId("u1");
        SparqlQueryContext.setWantsDraft(true);

        service.getOntologyAnnotations("p");

        assertEquals(List.of(true), scopeSeenByQueries.subList(0, 1));
    }

    @Test
    void clearingTheVersionIriRemovesItFromTheStoreToo() {
        service.updateOntologyIRIs("p", PUBLIC_IRI, "", false, null, null);

        String update = capturedUpdate();
        assertTrue(update.contains("owl:versionIRI ?v }"), update);
        assertFalse(update.contains("INSERT DATA { <" + PUBLIC_IRI + "> owl:versionIRI"), update);
    }

    @Test
    void deletingAnAnnotationOnlyRemovesTheValueInThatLanguage() {
        service.deleteOntologyAnnotation("p", "http://purl.org/dc/terms/title", "Pizza", "FR", false, null, null);

        String update = capturedUpdate();
        assertTrue(update.contains("FILTER(STR(?v) = \"Pizza\")"), update);
        assertTrue(update.contains("LCASE(LANG(?v)) = \"fr\""), update);
    }

    @Test
    void deletingAPlainAnnotationLeavesLanguageTaggedTwinsAlone() {
        service.deleteOntologyAnnotation("p", "http://purl.org/dc/terms/title", "Pizza", null, false, null, null);

        assertTrue(capturedUpdate().contains("LCASE(LANG(?v)) = \"\""));
    }

    @Test
    void settingTheVersionIriReplacesTheOldOne() {
        service.updateOntologyIRIs("p", PUBLIC_IRI, "http://ex.org/v2", false, null, null);

        String update = capturedUpdate();
        assertTrue(update.contains("DELETE { <" + PUBLIC_IRI + "> owl:versionIRI ?v }"), update);
        assertTrue(update.contains("INSERT DATA { <" + PUBLIC_IRI + "> owl:versionIRI <http://ex.org/v2> . }"), update);
    }
}
