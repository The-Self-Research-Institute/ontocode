package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DisjointAndMoveHistoryTest {

    private static final String DISJOINT = "http://www.w3.org/2002/07/owl#disjointWith";
    private static final String SUBCLASS = "http://www.w3.org/2000/01/rdf-schema#subClassOf";

    private Map<String, Object> record(MutationOp op) {
        OntologyHistoryService service = new OntologyHistoryService(mock(SparqlDatasetService.class));
        HistorySyncService sync = mock(HistorySyncService.class);
        ReflectionTestUtils.setField(service, "historySyncService", sync);
        ReflectionTestUtils.setField(service, "self", mock(OntologyHistoryService.class));
        service.recordGroupedMutations("proj", "u@x.com", "u@x.com", List.of(op), true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(sync).syncChange(eq("proj"), anyString(), data.capture());
        return data.getValue();
    }

    private static MutationOp op(String type, String iri, String value, String target) {
        return new MutationOp(type, iri, null, null, null, value, target, null, null, null, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> subs(Map<String, Object> row) {
        return (List<Map<String, String>>) row.get("subChanges");
    }

    @Test
    void addingADisjointClassIsARecordedStatementWithTheTarget() {
        Map<String, Object> row = record(op("addDisjointWith", "http://x/Test1", null, "http://x/test4"));
        assertEquals(1, subs(row).size());
        assertEquals(DISJOINT, subs(row).get(0).get("predicate"));
        assertEquals("http://x/test4", subs(row).get(0).get("newValue"));
        assertEquals("true", subs(row).get(0).get("addition"));
        assertEquals("addStatement", row.get("operationType"));
    }

    @Test
    void removingADisjointClassKeepsTheOldValue() {
        Map<String, Object> row = record(op("deleteDisjointWith", "http://x/Test1", null, "http://x/test4"));
        assertEquals("http://x/test4", subs(row).get(0).get("oldValue"));
        assertEquals("false", subs(row).get(0).get("addition"));
        assertEquals("removeStatement", row.get("operationType"));
    }

    @Test
    void changingAParentRecordsTheOldAndTheNewParent() {
        Map<String, Object> row = record(op("updateSubClassOf", "http://x/Test3", "http://x/old", "http://x/new"));
        assertEquals(2, subs(row).size());
        assertEquals("http://x/old", subs(row).get(0).get("oldValue"));
        assertEquals("false", subs(row).get(0).get("addition"));
        assertEquals("http://x/new", subs(row).get(1).get("newValue"));
        assertEquals("true", subs(row).get(1).get("addition"));
        assertEquals(SUBCLASS, subs(row).get(1).get("predicate"));
        assertEquals("updateSubClassOf", row.get("operationType"));
    }

    @Test
    void changingADisjointClassRecordsTheOldAndTheNewClass() {
        Map<String, Object> row = record(op("updateDisjointWith", "http://x/Test1", "http://x/old", "http://x/new"));
        assertEquals(2, subs(row).size());
        assertEquals(DISJOINT, subs(row).get(0).get("predicate"));
        assertEquals("http://x/old", subs(row).get(0).get("oldValue"));
        assertEquals("http://x/new", subs(row).get(1).get("newValue"));
        assertEquals("updateStatement", row.get("operationType"));
    }

    @Test
    void movingAClassIsOneEditedRowNotACreate() {
        OntologyHistoryService service = new OntologyHistoryService(mock(SparqlDatasetService.class));
        HistorySyncService sync = mock(HistorySyncService.class);
        ReflectionTestUtils.setField(service, "historySyncService", sync);
        ReflectionTestUtils.setField(service, "self", mock(OntologyHistoryService.class));
        service.recordGroupedMutations("proj", "u@x.com", "u@x.com", List.of(
                op("deleteSubClassOf", "http://x/Test3", null, "http://www.w3.org/2002/07/owl#Thing"),
                op("addSubClassOf", "http://x/Test3", null, "http://x/test4")), true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(sync).syncChange(eq("proj"), anyString(), data.capture());
        assertEquals("updateSubClassOf", data.getValue().get("operationType"));
        assertEquals(2, subs(data.getValue()).size());
    }

    private static self.research.ontology.owlEditor.model.HistoryChange.SubChange sub(String id, String predicate, String oldValue, String newValue, boolean addition) {
        var sc = new self.research.ontology.owlEditor.model.HistoryChange.SubChange();
        sc.setId(id);
        sc.setPredicate(predicate);
        sc.setOldValue(oldValue);
        sc.setNewValue(newValue);
        sc.setAddition(addition);
        return sc;
    }

    @Test
    void undoingAParentChangeRestoresTheOldParentAndRemovesTheNewOne() {
        var change = new self.research.ontology.owlEditor.model.HistoryChange();
        change.setEntityIRI("http://x/Test3");
        var plan = new RollbackMutationPlanner().planSubChanges(change, List.of(
                sub("1", SUBCLASS, "http://x/old", null, false),
                sub("2", SUBCLASS, null, "http://x/new", true)), true);
        assertEquals(List.of("addSubClassOf:http://x/old", "deleteSubClassOf:http://x/new"),
                plan.mutations().stream().map(m -> m.type() + ":" + m.target()).toList());
    }

    @Test
    void undoingADisjointAddRemovesIt() {
        var change = new self.research.ontology.owlEditor.model.HistoryChange();
        change.setEntityIRI("http://x/Test1");
        var plan = new RollbackMutationPlanner().planSubChanges(change, List.of(
                sub("1", DISJOINT, null, "http://x/test4", true)), true);
        assertEquals(1, plan.rawUpdates().size());
        assertEquals(true, plan.rawUpdates().get(0).contains("DELETE"));
        assertEquals(true, plan.unsupported().isEmpty());
    }

    private static final String DOMAIN = "http://www.w3.org/2000/01/rdf-schema#domain";
    private static final String RANGE = "http://www.w3.org/2000/01/rdf-schema#range";

    @Test
    void addingADomainIsARecordedStatementThatCanBeReversed() {
        Map<String, Object> row = record(op("addPropertyDomain", "http://x/hasWeight", null, "http://x/Vehicle"));
        assertEquals(1, subs(row).size());
        assertEquals(DOMAIN, subs(row).get(0).get("predicate"));
        assertEquals("http://x/Vehicle", subs(row).get(0).get("newValue"));
        assertEquals("true", subs(row).get(0).get("addition"));
        assertEquals("addStatement", row.get("operationType"));
    }

    @Test
    void removingADomainOrChangingARangeKeepsTheClassIri() {
        Map<String, Object> removed = record(op("deletePropertyDomain", "http://x/hasWeight", null, "http://x/Vehicle"));
        assertEquals("http://x/Vehicle", subs(removed).get(0).get("oldValue"));
        assertEquals("false", subs(removed).get(0).get("addition"));
        Map<String, Object> range = record(op("addPropertyRange", "http://x/hasWeight", null, "http://www.w3.org/2001/XMLSchema#decimal"));
        assertEquals(RANGE, subs(range).get(0).get("predicate"));
    }

    @Test
    void aRestrictionDomainIsNotSquashedIntoAPlainDomain() {
        MutationOp restriction = new MutationOp("addPropertyDomain", "http://x/hasWeight", null, null, null, null,
                "http://x/Vehicle", null, "some", null, null, null, null, null, null);
        Map<String, Object> row = record(restriction);
        assertEquals(null, row.get("subChanges"));
        assertEquals("addPropertyDomain", row.get("operationType"));
    }

    @Test
    void undoingADomainOrRangeAddRemovesIt() {
        var change = new self.research.ontology.owlEditor.model.HistoryChange();
        change.setEntityIRI("http://x/hasWeight");
        var plan = new RollbackMutationPlanner().planSubChanges(change, List.of(
                sub("1", DOMAIN, null, "http://x/Vehicle", true),
                sub("2", RANGE, null, "http://x/Range", true)), true);
        assertEquals(List.of("deletePropertyDomain:http://x/Vehicle", "deletePropertyRange:http://x/Range"),
                plan.mutations().stream().map(m -> m.type() + ":" + m.target()).toList());
    }

    @Test
    void undoingACharacteristicAddRemovesTheCharacteristicNotAClassAssertion() {
        var change = new self.research.ontology.owlEditor.model.HistoryChange();
        change.setEntityIRI("http://x/drives");
        String functional = "http://www.w3.org/2002/07/owl#FunctionalProperty";
        var plan = new RollbackMutationPlanner().planSubChanges(change, List.of(
                sub("1", "http://www.w3.org/1999/02/22-rdf-syntax-ns#type", null, functional, true)), true);
        assertEquals(List.of("deleteCharacteristic:" + functional),
                plan.mutations().stream().map(m -> m.type() + ":" + m.target()).toList());
    }

    @Test
    void undoingATypeThatIsAClassStaysAClassAssertion() {
        var change = new self.research.ontology.owlEditor.model.HistoryChange();
        change.setEntityIRI("http://x/car1");
        var plan = new RollbackMutationPlanner().planSubChanges(change, List.of(
                sub("1", "http://www.w3.org/1999/02/22-rdf-syntax-ns#type", null, "http://x/Car", true)), true);
        assertEquals(List.of("removeClassAssertion"), plan.mutations().stream().map(m -> m.type()).toList());
    }

    @Test
    void classificationUsesTheTargetAsTheNewValueOfAnUpdate() {
        MutationOp update = op("updateSubClassOf", "http://x/A", "http://x/old", "http://x/new");
        assertEquals("http://x/new", HistoryClassification.newValue(update));
        assertEquals("http://x/old", HistoryClassification.oldValue(update));
    }
}
