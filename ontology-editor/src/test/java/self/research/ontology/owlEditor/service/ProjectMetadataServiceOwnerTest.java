package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.owlEditor.TestDatabase;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.model.ProjectStatus;
import self.research.ontology.owlEditor.repository.JdbcProjectAccessRepository;
import self.research.ontology.owlEditor.repository.JdbcProjectRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProjectMetadataServiceOwnerTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final JdbcProjectRepository files = new JdbcProjectRepository(jdbc);
    private final ProjectMetadataService service = new ProjectMetadataService(files, new JdbcProjectAccessRepository(jdbc));

    @BeforeEach
    void clean() {
        jdbc.update("delete from project_member");
        jdbc.update("delete from project");
        jdbc.update("delete from ontology_file");
        jdbc.update("delete from app_user");
    }

    @Test
    void aNewProjectGetsTheVerifiedOwnerIdAlongsideTheEmail() {
        service.updateProjectMetadata("p1", ProjectStatus.uploaded("a.owl"), "g1", "ann@x.org", "u-ann", null, null);

        ProjectDocument stored = files.findById("p1").orElseThrow();
        assertEquals("u-ann", stored.getOwnerId());
        assertEquals("ann@x.org", stored.getOwnerEmail());
        assertEquals("g1", stored.getGridfsFileId());
        assertEquals("a.owl", stored.getFilename());
    }

    @Test
    void aLaterUploadNeverReplacesTheStoredOwner() {
        service.updateProjectMetadata("p1", ProjectStatus.uploaded("a.owl"), "g1", "ann@x.org", "u-ann", null, null);

        service.updateProjectMetadata("p1", ProjectStatus.uploaded("a.owl"), "g2", "bob@x.org", "u-member", null, null);

        ProjectDocument stored = files.findById("p1").orElseThrow();
        assertEquals("u-ann", stored.getOwnerId());
        assertEquals("ann@x.org", stored.getOwnerEmail());
        assertEquals("g2", stored.getGridfsFileId());
    }

    @Test
    void withoutAVerifiedIdTheOwnerIdIsLeftAlone() {
        service.updateProjectMetadata("p1", ProjectStatus.uploaded("a.owl"), "g1", "ann@x.org", null, null, null);
        service.updateProjectMetadata("p2", ProjectStatus.uploaded("a.owl"), "g1", "ann@x.org", " ", null, null);

        assertNull(files.findById("p1").orElseThrow().getOwnerId());
        assertNull(files.findById("p2").orElseThrow().getOwnerId());
    }

    @Test
    void theOwnerEmailComesFromTheWorkspaceProjectNotFromWhoUploaded() {
        jdbc.update("insert into project (id, project_id, workspace_id, owner_id) values ('row-p1', 'p1', 'ws-1', 'u-owner')");
        jdbc.update("insert into project_member (project_row_id, position, user_id, email, role) values ('row-p1', 0, 'u-up', 'uploader@x.org', 'EDITOR')");
        jdbc.update("insert into project_member (project_row_id, position, user_id, email, role) values ('row-p1', 1, 'u-owner', 'owner@x.org', 'OWNER')");
        ProjectDocument fileRecord = new ProjectDocument("p1", "p", "p.owl");
        fileRecord.setOwnerEmail("uploader@x.org");
        files.save(fileRecord);

        assertEquals(Optional.of("owner@x.org"), service.getOwnerEmail("p1"));
    }

    @Test
    void withoutAWorkspaceProjectTheFileRecordOwnerIsUsed() {
        ProjectDocument fileRecord = new ProjectDocument("p1", "p", "p.owl");
        fileRecord.setOwnerEmail("solo@x.org");
        files.save(fileRecord);

        assertEquals(Optional.of("solo@x.org"), service.getOwnerEmail("p1"));
        assertEquals(Optional.empty(), service.getOwnerEmail("missing"));
    }

    @Test
    void statusMetadataAndVersionsRoundTripThroughTheService() {
        service.writeStatus("p1", ProjectStatus.uploaded("a.owl"));
        service.writeMeta("p1", java.util.Map.of("k", "v"));
        service.writeImportProgress("p1", 150, "parse", "Parsing");
        service.setOwnerEmail("p1", "ann@x.org");
        service.setGridfsFileId("p1", "g7");

        assertEquals("a.owl", service.readStatus("p1").orElseThrow().filename());
        assertEquals("v", service.readMeta("p1").orElseThrow().get("k"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> progress = (java.util.Map<String, Object>) service.readMeta("p1").orElseThrow().get("importProgress");
        assertEquals(99, progress.get("progress"));
        assertEquals("parse", progress.get("stage"));
        assertEquals("ann@x.org", files.findById("p1").orElseThrow().getOwnerEmail());
        assertEquals("g7", files.findById("p1").orElseThrow().getGridfsFileId());
        assertEquals(1L, service.incrementMutationVersion("p1"));
        assertEquals(2L, service.incrementMutationVersion("p1"));
        assertEquals(2L, service.getMutationVersion("p1"));
        assertEquals(1L, service.incrementMainGraphRevision("p1"));
        assertEquals(1L, service.getMainGraphRevision("p1"));
        assertEquals(Optional.of("p1"), service.getExistingProjectId("a.owl", "ann@x.org"));
    }
}
