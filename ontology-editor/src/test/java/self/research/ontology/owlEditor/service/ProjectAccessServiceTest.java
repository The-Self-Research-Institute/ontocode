package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.repository.ProjectRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectAccessServiceTest {

    @Test
    void aFileLevelRecordWithoutMembersFallsBackToTheParentProjectAndIgnoresEmailCase() {
        ProjectRepository repository = mock(ProjectRepository.class);
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(repository.findById("proj-1--file-9")).thenReturn(Optional.of(new ProjectDocument("proj-1--file-9", "f", "f.owl")));
        when(mongo.exists(any(Query.class), eq(ProjectDocument.class), eq("projects"))).thenReturn(true);

        assertTrue(new ProjectAccessService(repository, mongo).hasProjectAccess("proj-1--file-9", "Barkat@x.org"));

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).exists(query.capture(), eq(ProjectDocument.class), eq("projects"));
        String sent = query.getValue().getQueryObject().toJson();
        assertTrue(sent.contains("proj-1"));
        assertTrue(sent.contains("Barkat@x.org") && sent.contains("\"i\""), sent);
    }

    @Test
    void anUnknownEmailIsStillRefused() {
        ProjectRepository repository = mock(ProjectRepository.class);
        MongoTemplate mongo = mock(MongoTemplate.class);
        assertFalse(new ProjectAccessService(repository, mongo).hasProjectAccess("proj-1", " "));
    }

    private static ProjectDocument ownedBy(String ownerId, String ownerEmail) {
        ProjectDocument doc = new ProjectDocument("proj-1", "p", "p.owl");
        doc.setOwnerId(ownerId);
        doc.setOwnerEmail(ownerEmail);
        return doc;
    }

    @Test
    void theOwnerIsRecognisedByUserIdEvenWhenTheEmailHasChanged() {
        ProjectRepository repository = mock(ProjectRepository.class);
        when(repository.findById("proj-1")).thenReturn(Optional.of(ownedBy("u-1", "old@x.org")));

        assertTrue(new ProjectAccessService(repository, mock(MongoTemplate.class))
                .hasProjectAccess("proj-1", "u-1", "new@x.org"));
    }

    @Test
    void aMemberIsRecognisedByUserId() {
        ProjectRepository repository = mock(ProjectRepository.class);
        ProjectDocument doc = ownedBy("u-owner", "owner@x.org");
        ProjectDocument.MemberRef member = new ProjectDocument.MemberRef();
        member.setUserId("u-2");
        doc.setMembers(java.util.List.of(member));
        when(repository.findById("proj-1")).thenReturn(Optional.of(doc));

        assertTrue(new ProjectAccessService(repository, mock(MongoTemplate.class))
                .hasProjectAccess("proj-1", "u-2", "someone@x.org"));
    }

    @Test
    void aDifferentUserIdWithAnotherEmailIsRefused() {
        ProjectRepository repository = mock(ProjectRepository.class);
        when(repository.findById("proj-1")).thenReturn(Optional.of(ownedBy("u-1", "owner@x.org")));

        assertFalse(new ProjectAccessService(repository, mock(MongoTemplate.class))
                .hasProjectAccess("proj-1", "u-9", "intruder@x.org"));
    }

    @Test
    void anOlderProjectWithoutIdsStillWorksByEmail() {
        ProjectRepository repository = mock(ProjectRepository.class);
        when(repository.findById("proj-1")).thenReturn(Optional.of(ownedBy(null, "owner@x.org")));

        assertTrue(new ProjectAccessService(repository, mock(MongoTemplate.class))
                .hasProjectAccess("proj-1", "u-1", "OWNER@x.org"));
    }

    @Test
    void theParentProjectLookupAlsoMatchesTheUserId() {
        ProjectRepository repository = mock(ProjectRepository.class);
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(repository.findById("proj-1--file-9")).thenReturn(Optional.empty());
        when(mongo.exists(any(Query.class), eq(ProjectDocument.class), eq("projects"))).thenReturn(true);

        assertTrue(new ProjectAccessService(repository, mongo).hasProjectAccess("proj-1--file-9", "u-1", "a@x.org"));

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).exists(query.capture(), eq(ProjectDocument.class), eq("projects"));
        String sent = query.getValue().getQueryObject().toJson();
        assertTrue(sent.contains("ownerId") && sent.contains("members.userId") || sent.contains("\"userId\""), sent);
        assertTrue(sent.contains("u-1"), sent);
    }
}
