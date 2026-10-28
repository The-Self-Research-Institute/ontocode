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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProjectMetadataServiceOwnerTest {

    private Document savedSet(String ownerId) {
        MongoTemplate mongo = mock(MongoTemplate.class);
        ProjectMetadataService service = new ProjectMetadataService(mock(ProjectRepository.class), mongo);

        service.updateProjectMetadata("p1", ProjectStatus.uploaded("a.owl"), "g1", "ann@x.org", ownerId, null, null);

        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongo).upsert(org.mockito.ArgumentMatchers.any(Query.class), update.capture(), eq(ProjectDocument.class));
        return (Document) update.getValue().getUpdateObject().get("$set");
    }

    @Test
    void aNewProjectGetsTheVerifiedOwnerIdAlongsideTheEmail() {
        Document set = savedSet("u-ann");

        assertEquals("u-ann", set.get("ownerId"));
        assertEquals("ann@x.org", set.get("ownerEmail"));
    }

    @Test
    void withoutAVerifiedIdTheOwnerIdIsLeftAlone() {
        assertFalse(savedSet(null).containsKey("ownerId"));
        assertFalse(savedSet(" ").containsKey("ownerId"));
    }
}
