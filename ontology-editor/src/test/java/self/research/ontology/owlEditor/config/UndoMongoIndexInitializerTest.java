package self.research.ontology.owlEditor.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UndoMongoIndexInitializerTest {

    private IndexOperations operations;
    private UndoMongoIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        MongoTemplate template = mock(MongoTemplate.class);
        operations = mock(IndexOperations.class);
        when(template.indexOps(anyString())).thenReturn(operations);
        when(operations.getIndexInfo()).thenReturn(List.of());
        initializer = new UndoMongoIndexInitializer(template);
    }

    @Test
    void theOperationIdIsMadeUniquePerProjectEvenWhenAutomaticIndexCreationIsOff() {
        initializer.ensureAll();

        ArgumentCaptor<Index> created = ArgumentCaptor.forClass(Index.class);
        verify(operations, times(UndoMongoIndexInitializer.requiredIndexes().size())).ensureIndex(created.capture());
        Index operationId = created.getAllValues().stream().filter(index -> "project_op".equals(index.getIndexOptions().get("name")))
                .findFirst().orElseThrow();
        assertEquals(true, operationId.getIndexOptions().get("unique"));
        assertEquals(true, operationId.getIndexOptions().get("sparse"));
        assertEquals(Map.of("projectId", 1, "opId", 1), operationId.getIndexKeys());
    }

    @Test
    void theHistoryLookupsUsedByUndoAreIndexed() {
        List<String> names = UndoMongoIndexInitializer.requiredIndexes().stream().map(UndoMongoIndexInitializer.Spec::name).toList();

        assertTrue(names.containsAll(List.of("project_draft_user_ts", "project_entity_ts", "project_changeset")));
    }

    @Test
    void anIndexThatAlreadyExistsIsLeftAlone() {
        IndexInfo existing = mock(IndexInfo.class);
        when(existing.getName()).thenReturn("project_op");
        when(operations.getIndexInfo()).thenReturn(List.of(existing));

        assertTrue(initializer.ensureAll().get("undo_intents.project_op"));
        verify(operations, times(UndoMongoIndexInitializer.requiredIndexes().size() - 1)).ensureIndex(any(Index.class));
    }

    @Test
    void aFailureIsReportedButNeverStopsTheServer() {
        doThrow(new IllegalStateException("duplicates exist")).when(operations).ensureIndex(any(Index.class));

        Map<String, Boolean> outcomes = initializer.ensureAll();

        assertFalse(outcomes.get("undo_intents.project_op"));
        verify(operations, never()).dropIndex(anyString());
    }
}
