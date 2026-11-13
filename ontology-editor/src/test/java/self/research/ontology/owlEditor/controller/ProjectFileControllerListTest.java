package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import self.research.ontology.owlEditor.config.CallerIdentity;
import self.research.ontology.owlEditor.repository.ProjectAccessRepository;
import self.research.ontology.owlEditor.service.ProjectAccessService;
import self.research.ontology.owlEditor.service.ProjectAuthorization;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.StorageManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectFileControllerListTest {

    @TempDir
    Path folder;

    private StorageManager storage;
    private ProjectAccessRepository repository;
    private ProjectAccessService access;
    private ProjectFileController controller;

    @BeforeEach
    void setUp() {
        storage = mock(StorageManager.class);
        repository = mock(ProjectAccessRepository.class);
        access = new ProjectAccessService(repository);
        ProjectMetadataService metadata = mock(ProjectMetadataService.class);
        when(metadata.readStatus(anyString())).thenReturn(Optional.empty());
        controller = new ProjectFileController(storage, metadata, new ProjectAuthorization(access, false));
    }

    private static ProjectAccessRepository.AccessibleProjects visible(Set<String> ids, Set<String> parents) {
        return new ProjectAccessRepository.AccessibleProjects(ids, parents);
    }

    private void onDisk(List<String> ids) throws Exception {
        when(storage.listProjectIds()).thenReturn(ids);
        for (String id : ids) {
            Path file = Files.writeString(folder.resolve(id.replace("--", "_") + ".owl"), "<a/>");
            when(storage.findCurrentOntology(id)).thenReturn(Optional.of(file));
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> listed(CallerIdentity caller) {
        ResponseEntity<Map<String, Object>> response = controller.listFiles(null, false, caller);
        return ((List<Object>) response.getBody().get("files")).stream()
                .map(Object::toString).collect(Collectors.toList());
    }

    @Test
    void onlyTheFilesOfProjectsTheCallerCanAccessAreListed() throws Exception {
        onDisk(List.of("p-mine", "p-theirs", "p-parent--child", "p-other--child"));
        when(repository.accessibleProjects("u-1", "me@x.org")).thenReturn(visible(Set.of("p-mine"), Set.of("p-parent")));

        List<String> files = listed(new CallerIdentity("u-1", "me@x.org"));

        assertEquals(2, files.size());
        assertEquals(true, files.stream().anyMatch(f -> f.contains("p-mine")));
        assertEquals(true, files.stream().anyMatch(f -> f.contains("p-parent--child")));
    }

    @Test
    void theSameProjectsAreVisibleAsWithTheOneByOneCheck() throws Exception {
        List<String> ids = List.of("a", "b", "c");
        when(repository.hasAccess(eq("a"), eq("a"), any(), any())).thenReturn(true);
        when(repository.hasAccess(eq("b"), eq("b"), any(), any())).thenReturn(true);
        when(repository.accessibleProjects("u-1", "me@x.org")).thenReturn(visible(Set.of("a", "b"), Set.of()));
        List<String> oneByOne = new ArrayList<>();
        for (String id : ids) {
            if (access.hasProjectAccess(id, "u-1", "me@x.org")) {
                oneByOne.add(id);
            }
        }

        assertEquals(oneByOne, new ProjectAuthorization(access, false)
                .accessibleAmong(new CallerIdentity("u-1", "me@x.org"), ids));
    }

    @Test
    void thePermissionLookupCountDoesNotGrowWithTheNumberOfProjects() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            ids.add("p-" + i);
        }
        onDisk(ids);
        when(repository.accessibleProjects("u-1", "me@x.org")).thenReturn(visible(Set.of("p-3"), Set.of()));

        List<String> files = listed(new CallerIdentity("u-1", "me@x.org"));

        assertEquals(1, files.size());
        verify(repository, times(1)).accessibleProjects(any(), any());
        verify(repository, never()).hasAccess(anyString(), anyString(), any(), any());
    }
}
