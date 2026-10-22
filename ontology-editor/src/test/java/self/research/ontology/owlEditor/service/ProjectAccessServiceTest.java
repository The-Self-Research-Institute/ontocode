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
}
