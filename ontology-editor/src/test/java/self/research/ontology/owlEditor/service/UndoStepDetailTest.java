package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UndoStepDetailTest {

    private static HistoryChange entry(String operation, String type, String label, String oldValue, String newValue) {
        HistoryChange change = new HistoryChange();
        change.setOperationType(operation);
        change.setEntityType(type);
        change.setEntityLabel(label);
        change.setEntityIRI("http://example.org/o#" + (label == null ? "Thing" : label));
        change.setOldValue(oldValue);
        change.setNewValue(newValue);
        return change;
    }

    @Test
    void describesSourceAffectedAndRestoringForADomainChange() {
        UndoStepDetail.Detail detail = UndoStepDetail.of(List.of(
                entry("changeDomain", "PROPERTY", "hasParent", "http://example.org/o#Person", "http://example.org/o#Animal")));
        assertEquals("Entities → Properties", detail.source());
        assertEquals("hasParent → Domain", detail.affected());
        assertEquals("Animal → Person", detail.restoring());
    }

    @Test
    void leavesRestoringEmptyWhenNoBeforeAndAfterWereRecorded() {
        UndoStepDetail.Detail detail = UndoStepDetail.of(List.of(entry("createClass", "CLASS", "Dog", null, null)));
        assertEquals("Dog", detail.affected());
        assertNull(detail.restoring());
    }

    @Test
    void namesTheAssistantAsSource() {
        HistoryChange change = entry("createClass", "CLASS", "Dog", null, null);
        change.setSource("AI");
        assertEquals("AI assistant", UndoStepDetail.of(List.of(change)).source());
    }

    @Test
    void summarisesAChangeSet() {
        UndoStepDetail.Detail detail = UndoStepDetail.of(List.of(
                entry("createClass", "CLASS", "A", null, null), entry("createClass", "CLASS", "B", null, null)));
        assertEquals("2 changes", detail.affected());
        assertNull(detail.restoring());
    }
}
