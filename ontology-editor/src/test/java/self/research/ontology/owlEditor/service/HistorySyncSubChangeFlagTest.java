package self.research.ontology.owlEditor.service;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class HistorySyncSubChangeFlagTest {

    @Test
    void theArrayFilterMatchesSubChangesByTheirStoredUnderscoreIdField() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        HistorySyncService sync = new HistorySyncService(mock(HistoryChangeRepository.class), mongo,
                mock(OntologyHistoryService.class));

        sync.setSubChangesReverted("change-1", List.of("sub-1"), true, "audit-1");

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(any(Query.class), update.capture(), eq(HistoryChange.class));
        Document filter = (Document) update.getValue().getArrayFilters().get(0).asDocument();
        assertEquals(Document.parse("{\"sc._id\": {\"$in\": [\"sub-1\"]}}"), filter);
        Document set = (Document) update.getValue().getUpdateObject().get("$set");
        assertEquals(true, set.get("subChanges.$[sc].reverted"));
        assertEquals("audit-1", set.get("subChanges.$[sc].revertedAuditId"));
    }
}
