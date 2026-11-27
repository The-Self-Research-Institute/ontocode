package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OwlApiMutationCoordinatorDraftTest {

    private final DesktopOntologyLoader loader = mock(DesktopOntologyLoader.class);
    private final OwlApiMutationPatcher patcher = mock(OwlApiMutationPatcher.class);

    private OwlApiMutationCoordinator coordinator() {
        when(loader.warmProject(anyString(), anyLong())).thenReturn(Map.of("ready", true));
        return new OwlApiMutationCoordinator(mock(ProjectOntologyCache.class), mock(ProjectMetadataService.class), patcher,
                loader, mock(SparqlDatasetService.class), true);
    }

    @Test
    void aWriteTheMemoryModelCouldNotTakeRefreshesTheDraftBeforeTheModelIsReloadedFromIt() {
        when(loader.isOwlApiFirst()).thenReturn(true);
        when(loader.hasDraft("p")).thenReturn(true);

        coordinator().afterMutation("p", List.of());

        InOrder order = inOrder(loader);
        order.verify(loader).refreshDraftFromTripleStore("p");
        order.verify(loader).warmProject("p", 5_000);
    }

    @Test
    void withoutADraftTheModelIsReloadedFromTheTripleStoreAsBefore() {
        when(loader.isOwlApiFirst()).thenReturn(true);
        when(loader.hasDraft("p")).thenReturn(false);

        coordinator().afterMutation("p", List.of());

        verify(loader, never()).refreshDraftFromTripleStore("p");
        verify(loader).warmProject("p", 5_000);
    }

    @Test
    void anEditThePatcherTookLeavesTheDraftAlone() {
        when(loader.isOwlApiFirst()).thenReturn(true);
        when(loader.hasDraft("p")).thenReturn(true);
        when(patcher.tryPatch("p", List.of())).thenReturn(true);

        coordinator().afterMutation("p", List.of());

        verify(loader, never()).refreshDraftFromTripleStore("p");
    }
}
