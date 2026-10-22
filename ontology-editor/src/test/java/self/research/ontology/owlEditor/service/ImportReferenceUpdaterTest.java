package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.repository.ProjectRepository;
import self.research.ontology.owlEditor.service.ImportReferenceUpdater.Actor;
import self.research.ontology.owlEditor.service.ImportReferenceUpdater.Result;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportReferenceUpdaterTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final IRI IMPORTS = VF.createIRI("http://www.w3.org/2002/07/owl#imports");
    private static final String OLD = "http://x.org/pizza";
    private static final String NEW = "http://x.org/pizza-v2";
    private static final Actor ACTOR = new Actor("user-1", "me@x.org", "Me");

    private SparqlDatasetService datasets;
    private ProjectRepository projects;
    private WorkspaceOwnershipService ownership;
    private OntologyMutationService mutations;
    private SailRepository store;

    @BeforeEach
    void setUp() {
        datasets = mock(SparqlDatasetService.class);
        projects = mock(ProjectRepository.class);
        ownership = mock(WorkspaceOwnershipService.class);
        mutations = mock(OntologyMutationService.class);
        store = new SailRepository(new MemoryStore());
        store.init();
        when(datasets.getRepository()).thenReturn(store);
        when(datasets.execAsk(anyString(), anyString())).thenReturn(true);
    }

    private ImportReferenceUpdater updater(boolean desktop) {
        return new ImportReferenceUpdater(datasets, projects, mock(MongoTemplate.class), ownership, mutations,
                new ProjectWriteLockRegistry(), desktop);
    }

    private void importer(String projectId, String name, String owner) {
        IRI graph = VF.createIRI(SparqlGraphUris.mainProjectGraph(projectId));
        try (RepositoryConnection c = store.getConnection()) {
            c.add(VF.createIRI("http://x.org/" + projectId), IMPORTS, VF.createIRI(OLD), graph);
        }
        ProjectDocument document = mock(ProjectDocument.class);
        when(document.getName()).thenReturn(name);
        when(document.getOwnerEmail()).thenReturn(owner);
        when(projects.findById(projectId)).thenReturn(Optional.of(document));
    }

    @Test
    void anImporterTheUserCanEditIsUpdatedThroughItsOwnHistory() {
        importer("restaurant", "Restaurant", "sam@x.org");
        when(ownership.canPublishToProject("user-1", "restaurant")).thenReturn(true);

        Result result = updater(false).update("pizza", OLD, NEW, ACTOR, null);

        assertEquals(List.of(new ImportReferenceUpdater.Updated("restaurant", "Restaurant")), result.updated());
        assertTrue(result.skipped().isEmpty());
        verify(mutations).applyRawUpdateWithHistory(eq("restaurant"), contains("<" + NEW + ">"), eq(false), eq("me@x.org"), eq("Me"));
    }

    @Test
    void theOwnerOfAProjectOutsideAnyWorkspaceMayEditItByEmail() {
        importer("solo", "Solo", "ME@x.org");
        when(ownership.canPublishToProject("user-1", "solo")).thenReturn(false);

        Result result = updater(false).update("pizza", OLD, NEW, ACTOR, null);

        assertEquals(1, result.updated().size());
    }

    @Test
    void anImporterTheUserCannotEditIsLeftAloneAndReportedWithItsOwner() {
        importer("menu", "Menu", "sam@x.org");
        when(ownership.canPublishToProject("user-1", "menu")).thenReturn(false);

        Result result = updater(false).update("pizza", OLD, NEW, ACTOR, null);

        assertTrue(result.updated().isEmpty());
        assertEquals(1, result.skipped().size());
        assertEquals("Menu", result.skipped().get(0).name());
        assertEquals("sam@x.org", result.skipped().get(0).owner());
        assertTrue(result.skipped().get(0).reason().contains("edit rights"));
        verify(mutations, never()).applyRawUpdateWithHistory(anyString(), anyString(), anyBoolean(), anyString(), anyString());
    }

    @Test
    void theProjectBeingRenamedIsNeverTouched() {
        importer("pizza", "Pizza", "me@x.org");

        Result result = updater(false).update("pizza", OLD, NEW, ACTOR, null);

        assertTrue(result.updated().isEmpty());
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    void aProjectThatDoesNotReallyImportTheOldIriIsIgnored() {
        importer("stale", "Stale", "sam@x.org");
        when(datasets.execAsk(eq("stale"), anyString())).thenReturn(false);

        Result result = updater(false).update("pizza", OLD, NEW, ACTOR, null);

        assertTrue(result.updated().isEmpty());
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    void aFailureInOneProjectIsReportedAndTheOthersStillRun() throws Exception {
        importer("broken", "Broken", "sam@x.org");
        importer("fine", "Fine", "sam@x.org");
        when(ownership.canPublishToProject(anyString(), anyString())).thenReturn(true);
        doThrow(new IllegalStateException("store offline")).when(mutations)
                .applyRawUpdateWithHistory(eq("broken"), anyString(), anyBoolean(), anyString(), anyString());

        Result result = updater(false).update("pizza", OLD, NEW, ACTOR, null);

        assertEquals(1, result.updated().size());
        assertEquals("fine", result.updated().get(0).projectId());
        assertEquals(1, result.skipped().size());
        assertEquals("broken", result.skipped().get(0).projectId());
    }

    @Test
    void anUnverifiedUserCannotUpdateAnyoneElsesProjectButDesktopOwnsEverything() {
        importer("restaurant", "Restaurant", "sam@x.org");

        assertEquals(1, updater(false).update("pizza", OLD, NEW, new Actor(null, null, null), null).skipped().size());
        assertEquals(1, updater(true).update("pizza", OLD, NEW, new Actor(null, "me@x.org", "Me"), null).updated().size());
    }

    @Test
    void nothingHappensWhenTheIriDidNotChangeOrIsUnsafe() {
        importer("restaurant", "Restaurant", "sam@x.org");

        assertTrue(updater(true).update("pizza", OLD, OLD, ACTOR, null).updated().isEmpty());
        assertTrue(updater(true).update("pizza", "http://x.org/a>b", NEW, ACTOR, null).updated().isEmpty());
        assertTrue(updater(true).update("pizza", null, NEW, ACTOR, null).updated().isEmpty());
    }
}
