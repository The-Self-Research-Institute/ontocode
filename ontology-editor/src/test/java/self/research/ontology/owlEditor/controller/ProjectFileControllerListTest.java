package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import self.research.ontology.owlEditor.config.CallerIdentity;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.repository.ProjectRepository;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectFileControllerListTest {

    @TempDir
    Path folder;

    private StorageManager storage;
    private MongoTemplate mongo;
    private ProjectRepository repository;
    private ProjectAccessService access;
    private ProjectFileController controller;

    @BeforeEach
    void setUp() {
        storage = mock(StorageManager.class);
        mongo = mock(MongoTemplate.class);
        repository = mock(ProjectRepository.class);
        access = new ProjectAccessService(repository, mongo);
        ProjectMetadataService metadata = mock(ProjectMetadataService.class);
        when(metadata.readStatus(anyString())).thenReturn(Optional.empty());
        controller = new ProjectFileController(storage, metadata, new ProjectAuthorization(access, false));
    }

    private ProjectDocument doc(String id, String parent, String ownerId, String ownerEmail) {
        ProjectDocument doc = new ProjectDocument();
        doc.setId(id);
        doc.setProjectId(parent);
        doc.setOwnerId(ownerId);
        doc.setOwnerEmail(ownerEmail);
        return doc;
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
        when(mongo.find(any(Query.class), eq(ProjectDocument.class), eq("projects"))).thenReturn(List.of(
                doc("p-mine", null, "u-1", "me@x.org"),
                doc("p-parent", "p-parent", "u-1", "me@x.org")));

        List<String> files = listed(new CallerIdentity("u-1", "me@x.org"));

        assertEquals(2, files.size());
        assertEquals(true, files.stream().anyMatch(f -> f.contains("p-mine")));
        assertEquals(true, files.stream().anyMatch(f -> f.contains("p-parent--child")));
    }

    @Test
    void theSameProjectsAreVisibleAsWithTheOneByOneCheck() throws Exception {
        List<String> ids = List.of("a", "b", "c");
        ProjectDocument a = doc("a", null, "u-1", null);
        ProjectDocument b = doc("b", null, "u-2", "ME@x.org");
        ProjectDocument c = doc("c", null, "u-3", "other@x.org");
        when(repository.findById("a")).thenReturn(Optional.of(a));
        when(repository.findById("b")).thenReturn(Optional.of(b));
        when(repository.findById("c")).thenReturn(Optional.of(c));
        when(mongo.exists(any(Query.class), eq(ProjectDocument.class), eq("projects"))).thenReturn(false);
        when(mongo.find(any(Query.class), eq(ProjectDocument.class), eq("projects"))).thenReturn(List.of(a, b));
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
        when(mongo.find(any(Query.class), eq(ProjectDocument.class), eq("projects")))
                .thenReturn(List.of(doc("p-3", null, "u-1", "me@x.org")));

        List<String> files = listed(new CallerIdentity("u-1", "me@x.org"));

        assertEquals(1, files.size());
        verify(mongo, times(1)).find(any(Query.class), eq(ProjectDocument.class), eq("projects"));
        verify(mongo, never()).exists(any(Query.class), eq(ProjectDocument.class), anyString());
        verify(repository, never()).findById(anyString());
    }
}
