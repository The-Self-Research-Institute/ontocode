package self.research.ontology.owlEditor.service;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.model.ProjectStatus;
import self.research.ontology.owlEditor.repository.ProjectRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectMetadataServiceOwnerTest {

    private Document savedUpdate(String ownerId) {
        MongoTemplate mongo = mock(MongoTemplate.class);
        ProjectMetadataService service = new ProjectMetadataService(mock(ProjectRepository.class), mongo);

        service.updateProjectMetadata("p1", ProjectStatus.uploaded("a.owl"), "g1", "ann@x.org", ownerId, null, null);

        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongo).upsert(org.mockito.ArgumentMatchers.any(Query.class), update.capture(), eq(ProjectDocument.class));
        return update.getValue().getUpdateObject();
    }

    @Test
    void aNewProjectGetsTheVerifiedOwnerIdAlongsideTheEmail() {
        Document setOnInsert = (Document) savedUpdate("u-ann").get("$setOnInsert");

        assertEquals("u-ann", setOnInsert.get("ownerId"));
        assertEquals("ann@x.org", setOnInsert.get("ownerEmail"));
    }

    @Test
    void aLaterUploadNeverReplacesTheStoredOwner() {
        Document set = (Document) savedUpdate("u-member").get("$set");

        assertFalse(set.containsKey("ownerId"));
        assertFalse(set.containsKey("ownerEmail"));
    }

    @Test
    void withoutAVerifiedIdTheOwnerIdIsLeftAlone() {
        assertFalse(((Document) savedUpdate(null).get("$setOnInsert")).containsKey("ownerId"));
        assertFalse(((Document) savedUpdate(" ").get("$setOnInsert")).containsKey("ownerId"));
    }

    @Test
    void theOwnerEmailComesFromTheWorkspaceProjectNotFromWhoUploaded() {
        ProjectRepository repository = mock(ProjectRepository.class);
        MongoTemplate mongo = mock(MongoTemplate.class);
        ProjectDocument fileRecord = new ProjectDocument("p1", "p", "p.owl");
        fileRecord.setOwnerEmail("uploader@x.org");
        when(repository.findById("p1")).thenReturn(Optional.of(fileRecord));
        ProjectDocument workspaceRecord = new ProjectDocument();
        ProjectDocument.MemberRef owner = new ProjectDocument.MemberRef();
        owner.setRole("OWNER");
        owner.setEmail("owner@x.org");
        ProjectDocument.MemberRef editor = new ProjectDocument.MemberRef();
        editor.setRole("EDITOR");
        editor.setEmail("uploader@x.org");
        workspaceRecord.setMembers(List.of(editor, owner));
        when(mongo.findOne(org.mockito.ArgumentMatchers.any(Query.class), eq(ProjectDocument.class), eq("projects")))
                .thenReturn(workspaceRecord);

        assertEquals(Optional.of("owner@x.org"), new ProjectMetadataService(repository, mongo).getOwnerEmail("p1"));
    }

    @Test
    void withoutAWorkspaceProjectTheFileRecordOwnerIsUsed() {
        ProjectRepository repository = mock(ProjectRepository.class);
        ProjectDocument fileRecord = new ProjectDocument("p1", "p", "p.owl");
        fileRecord.setOwnerEmail("solo@x.org");
        when(repository.findById("p1")).thenReturn(Optional.of(fileRecord));

        assertTrue(new ProjectMetadataService(repository, mock(MongoTemplate.class)).getOwnerEmail("p1").isPresent());
        assertEquals("solo@x.org", new ProjectMetadataService(repository, mock(MongoTemplate.class)).getOwnerEmail("p1").get());
    }
}
