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

    private static ProjectDocument workspaceProject(String ownerId, String ownerEmail) {
        ProjectDocument record = new ProjectDocument();
        record.setOwnerId(ownerId);
        ProjectDocument.MemberRef owner = new ProjectDocument.MemberRef();
        owner.setUserId(ownerId);
        owner.setEmail(ownerEmail);
        owner.setRole("OWNER");
        ProjectDocument.MemberRef editor = new ProjectDocument.MemberRef();
        editor.setUserId("u-editor");
        editor.setEmail("editor@x.org");
        editor.setRole("EDITOR");
        record.setMembers(java.util.List.of(owner, editor));
        return record;
    }

    private static ProjectAccessService withWorkspaceProject(ProjectDocument fileRecord, ProjectDocument workspaceRecord) {
        ProjectRepository repository = mock(ProjectRepository.class);
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(repository.findById("proj-1")).thenReturn(Optional.ofNullable(fileRecord));
        when(mongo.findOne(any(Query.class), eq(ProjectDocument.class), eq("projects"))).thenReturn(workspaceRecord);
        return new ProjectAccessService(repository, mongo);
    }

    @Test
    void aMemberWhoUploadedTheFileIsNotTheOwner() {
        ProjectAccessService service = withWorkspaceProject(
                ownedBy("u-editor", "editor@x.org"), workspaceProject("u-owner", "owner@x.org"));

        assertFalse(service.isOwner("proj-1", "u-editor", "editor@x.org"));
        assertFalse(service.isProjectOwner("proj-1", "editor@x.org"));
    }

    @Test
    void theRealOwnerStaysTheOwnerEvenAfterAMemberUploaded() {
        ProjectAccessService service = withWorkspaceProject(
                ownedBy("u-editor", "editor@x.org"), workspaceProject("u-owner", "owner@x.org"));

        assertTrue(service.isOwner("proj-1", "u-owner", "owner@x.org"));
        assertTrue(service.isProjectOwner("proj-1", "OWNER@x.org"));
    }

    @Test
    void withoutAWorkspaceProjectTheFileRecordOwnerStillCounts() {
        ProjectAccessService service = withWorkspaceProject(ownedBy("u-solo", "solo@x.org"), null);

        assertTrue(service.isOwner("proj-1", "u-solo", "solo@x.org"));
        assertFalse(service.isOwner("proj-1", "u-other", "other@x.org"));
    }

    private static ProjectDocument withMembers(String id, String... emails) {
        ProjectDocument doc = new ProjectDocument(id, "p", "p.owl");
        java.util.List<ProjectDocument.MemberRef> members = new java.util.ArrayList<>();
        for (String email : emails) {
            ProjectDocument.MemberRef ref = new ProjectDocument.MemberRef();
            ref.setEmail(email);
            members.add(ref);
        }
        doc.setMembers(members);
        return doc;
    }

    @Test
    void aProjectWithMoreThanOneMemberIsShared() {
        ProjectRepository repository = mock(ProjectRepository.class);
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.findOne(any(Query.class), eq(ProjectDocument.class), eq("projects")))
                .thenReturn(withMembers("proj-1", "owner@x.org", "editor@x.org"));

        assertTrue(new ProjectAccessService(repository, mongo).isShared("proj-1--file-9"));
    }

    @Test
    void aProjectWithOnlyItsOwnerIsNotShared() {
        ProjectRepository repository = mock(ProjectRepository.class);
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.findOne(any(Query.class), eq(ProjectDocument.class), eq("projects")))
                .thenReturn(withMembers("proj-1", "owner@x.org"));

        assertFalse(new ProjectAccessService(repository, mongo).isShared("proj-1"));
    }

    @Test
    void anUnknownOrBlankProjectIsNotShared() {
        ProjectAccessService service = new ProjectAccessService(mock(ProjectRepository.class), mock(MongoTemplate.class));

        assertFalse(service.isShared("missing"));
        assertFalse(service.isShared(" "));
        assertFalse(service.isShared(null));
    }
}
