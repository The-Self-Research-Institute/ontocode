package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationGroupsTest {

    private MongoTemplate mongo;
    private OperationGroups groups;

    @BeforeEach
    void setUp() {
        mongo = mock(MongoTemplate.class);
        groups = new OperationGroups(mongo);
    }

    private static HistoryChange entry(String id, String projectId, String group) {
        HistoryChange change = new HistoryChange.Builder(projectId, "edit-" + id, "u1", "User").operationType("updateOntologyIRI")
                .build();
        change.setId(id);
        change.setOperationGroupId(group);
        return change;
    }

    @Test
    void anEntryOutsideAnyGroupGivesNoWarning() {
        assertNull(groups.warning(List.of(entry("a", "source", null))));
    }

    @Test
    void anEntryWhoseGroupHasOtherProjectsStillChangedNamesThem() {
        HistoryChange source = entry("a", "source", "g1");
        when(mongo.find(any(Query.class), eq(HistoryChange.class)))
                .thenReturn(List.of(source, entry("b", "restaurant", "g1")));

        String warning = groups.warning(List.of(source));

        assertNotNull(warning);
        assertTrue(warning.contains("restaurant"));
    }

    @Test
    void siblingsInTheSameProjectAreNotReported() {
        HistoryChange source = entry("a", "source", "g1");
        when(mongo.find(any(Query.class), eq(HistoryChange.class)))
                .thenReturn(List.of(source, entry("c", "source", "g1")));

        assertNull(groups.warning(List.of(source)));
    }
}
