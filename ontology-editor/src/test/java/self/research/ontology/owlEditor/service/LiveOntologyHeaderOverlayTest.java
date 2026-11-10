package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveOntologyHeaderOverlayTest {

    private static final String PROJECT = "proj-1";

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private ProjectOntologyCache cache;
    private DesktopOwlApiMutationService desktop;
    private OntologyMetadataService service;
    private ProjectMetadataService projectMetadataService;

    @BeforeEach
    void setUp() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(new OWLOntologyID(
                Optional.of(IRI.create("http://ex.org/onto")), Optional.of(IRI.create("http://ex.org/onto/1.0"))));
        var factory = manager.getOWLDataFactory();
        manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(ontology, factory.getOWLAnnotation(
                factory.getOWLAnnotationProperty(IRI.create("http://purl.org/dc/elements/1.1/title")),
                factory.getOWLLiteral("Circulatory system"))));
        manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(ontology, factory.getOWLAnnotation(
                factory.getOWLAnnotationProperty(IRI.create("http://www.w3.org/2000/01/rdf-schema#comment")),
                factory.getOWLLiteral("A header comment", "en"))));

        cache = mock(ProjectOntologyCache.class);
        when(cache.get(PROJECT)).thenReturn(Optional.of(new ProjectOntologyCache.CachedOntology(ontology, null, manager, false)));
        desktop = mock(DesktopOwlApiMutationService.class);
        when(desktop.isAuthoritative(PROJECT)).thenReturn(true);

        projectMetadataService = mock(ProjectMetadataService.class);
        service = new OntologyMetadataService(mock(SparqlDatasetService.class), projectMetadataService,
                mock(OntologyMutationService.class), mock(GeneralClassAxiomService.class), mock(ProjectImportService.class),
                mock(ManchesterExpressionService.class), mock(StorageManager.class));
        ReflectionTestUtils.setField(service, "ontologyCache", cache);
        ReflectionTestUtils.setField(service, "desktopOwlApi", desktop);
    }

    @AfterEach
    void tearDown() {
        SparqlQueryContext.clear();
    }

    @Test
    void theLiveHeaderFillsInAnEmptyCachedHeaderOnDesktop() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("annotations", List.of());

        LiveOntologyHeaderOverlay.apply(metadata, PROJECT, cache, desktop);

        assertEquals("http://ex.org/onto", metadata.get("ontologyIRI"));
        assertEquals("http://ex.org/onto/1.0", metadata.get("versionIRI"));
        List<?> annotations = (List<?>) metadata.get("annotations");
        assertEquals(2, annotations.size(), annotations.toString());
        assertEquals("en", ((Map<?, ?>) annotations.stream()
                .filter(a -> "A header comment".equals(((Map<?, ?>) a).get("value"))).findFirst().orElseThrow()).get("language"));
    }

    @Test
    void aVersionIriThatWasRemovedFromTheLiveModelIsDroppedFromStaleMetadata() throws Exception {
        OWLOntology noVersion = manager.createOntology(IRI.create("http://ex.org/other"));
        Map<String, Object> metadata = new HashMap<>(Map.of("versionIRI", "http://ex.org/stale/1.0"));

        LiveOntologyHeaderOverlay.overlay(metadata, noVersion);

        assertEquals("http://ex.org/other", metadata.get("ontologyIRI"));
        assertFalse(metadata.containsKey("versionIRI"));
    }

    @Test
    void nothingChangesWhenTheModelIsNotTheLiveSource() {
        when(desktop.isAuthoritative(PROJECT)).thenReturn(false);
        Map<String, Object> metadata = new HashMap<>();

        LiveOntologyHeaderOverlay.apply(metadata, PROJECT, cache, desktop);
        LiveOntologyHeaderOverlay.apply(metadata, PROJECT, null, null);

        assertNull(metadata.get("ontologyIRI"));
        assertNull(metadata.get("annotations"));
    }

    @Test
    void cachedMetadataWrittenBeforeTheImportFinishedStillShowsTheHeaderOnDesktop() {
        Map<String, Object> staleCache = new HashMap<>();
        staleCache.put("cacheComplete", true);
        staleCache.put("counts", Map.of("classes", 0));
        staleCache.put("annotations", List.of());
        when(projectMetadataService.readMeta(PROJECT)).thenReturn(Optional.of(staleCache));

        Map<String, Object> metadata = service.getMetadata(PROJECT);

        assertEquals("http://ex.org/onto", metadata.get("ontologyIRI"));
        assertEquals("http://ex.org/onto/1.0", metadata.get("versionIRI"));
        assertEquals(2, ((List<?>) metadata.get("annotations")).size());
    }

    @Test
    void cachedMetadataIsLeftAloneOnWebWhereThereIsNoLiveModel() {
        ReflectionTestUtils.setField(service, "desktopOwlApi", null);
        Map<String, Object> staleCache = new HashMap<>();
        staleCache.put("cacheComplete", true);
        staleCache.put("counts", Map.of("classes", 3));
        staleCache.put("ontologyIRI", "http://ex.org/cached");
        when(projectMetadataService.readMeta(PROJECT)).thenReturn(Optional.of(staleCache));

        Map<String, Object> metadata = service.getMetadata(PROJECT);

        assertEquals("http://ex.org/cached", metadata.get("ontologyIRI"));
        Mockito.verifyNoInteractions(cache);
    }
}
