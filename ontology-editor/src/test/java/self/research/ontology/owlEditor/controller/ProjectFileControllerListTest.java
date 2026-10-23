package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import self.research.ontology.owlEditor.config.CallerIdentity;
import self.research.ontology.owlEditor.service.ProjectAccessService;
import self.research.ontology.owlEditor.service.ProjectAuthorization;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.StorageManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectFileControllerListTest {

    @TempDir
    Path folder;

    @Test
    void onlyTheFilesOfProjectsTheCallerCanAccessAreListed() throws Exception {
        Path mine = Files.writeString(folder.resolve("mine.owl"), "<a/>");
        Path theirs = Files.writeString(folder.resolve("theirs.owl"), "<b/>");
        StorageManager storage = mock(StorageManager.class);
        when(storage.listProjectIds()).thenReturn(List.of("p-mine", "p-theirs"));
        when(storage.findCurrentOntology("p-mine")).thenReturn(Optional.of(mine));
        when(storage.findCurrentOntology("p-theirs")).thenReturn(Optional.of(theirs));
        ProjectMetadataService metadata = mock(ProjectMetadataService.class);
        when(metadata.readStatus("p-mine")).thenReturn(Optional.empty());
        when(metadata.readStatus("p-theirs")).thenReturn(Optional.empty());
        ProjectAccessService access = mock(ProjectAccessService.class);
        when(access.hasProjectAccess("p-mine", "u-1", "me@x.org")).thenReturn(true);
        ProjectFileController controller = new ProjectFileController(storage, metadata, new ProjectAuthorization(access, false));

        ResponseEntity<Map<String, Object>> response = controller.listFiles(null, false, new CallerIdentity("u-1", "me@x.org"));

        List<?> files = (List<?>) response.getBody().get("files");
        assertEquals(1, files.size());
        assertTrue(files.get(0).toString().contains("p-mine"));
    }
}
