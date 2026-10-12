package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryCreationParentTest {

    private static final String SUBCLASS = "http://www.w3.org/2000/01/rdf-schema#subClassOf";
    private static final String THING = "http://www.w3.org/2002/07/owl#Thing";

    private static MutationOp create(String parent) {
        return new MutationOp("createClass", "http://ex/Admin", "Admin", parent, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    @Test
    void aNewSubclassRecordsItsParentSoARedoPutsItBackUnderIt() {
        List<Map<String, String>> subChanges = new ArrayList<>();

        OntologyHistoryService.addCreationParent(create("http://ex/Person"), subChanges);

        assertEquals(1, subChanges.size());
        assertEquals(SUBCLASS, subChanges.get(0).get("predicate"));
        assertEquals("http://ex/Person", subChanges.get(0).get("newValue"));
        assertEquals("true", subChanges.get(0).get("addition"));
    }

    @Test
    void aTopLevelClassOrAParentAlreadyRecordedAddsNothing() {
        List<Map<String, String>> subChanges = new ArrayList<>();
        OntologyHistoryService.addCreationParent(create(THING), subChanges);
        assertTrue(subChanges.isEmpty());

        Map<String, String> existing = new HashMap<>();
        existing.put("predicate", SUBCLASS);
        existing.put("newValue", "http://ex/Person");
        subChanges.add(existing);
        OntologyHistoryService.addCreationParent(create("http://ex/Person"), subChanges);
        assertEquals(1, subChanges.size());
    }

    @Test
    void redoOfACreatedSubclassRebuildsItUnderTheRecordedParent() {
        var change = new self.research.ontology.owlEditor.model.HistoryChange();
        change.setOperationType("createClass");
        change.setEntityIRI("http://ex/Admin");
        var sc = new self.research.ontology.owlEditor.model.HistoryChange.SubChange();
        sc.setId("s1");
        sc.setPredicate(SUBCLASS);
        sc.setNewValue("http://ex/Person");
        sc.setAddition(true);

        var plan = new RollbackMutationPlanner().planRedo(change, List.of(sc));

        assertEquals("http://ex/Person", plan.mutations().get(0).parent());
    }
}
