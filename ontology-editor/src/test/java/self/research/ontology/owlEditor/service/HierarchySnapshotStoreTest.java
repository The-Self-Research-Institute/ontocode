package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.TestDatabase;
import self.research.ontology.owlEditor.document.HierarchySnapshotDoc;
import self.research.ontology.owlEditor.dto.OntologyDto;
import self.research.ontology.owlEditor.repository.JdbcHierarchySnapshotRepository;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HierarchySnapshotStoreTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private HierarchySnapshotStore store;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from hierarchy_snapshot");
        store = new HierarchySnapshotStore(new JdbcHierarchySnapshotRepository(jdbc));
    }

    private static OntologyDto.TreeNode node(String id) {
        OntologyDto.TreeNode n = new OntologyDto.TreeNode();
        n.setId(id);
        n.setLabel("Label of " + id + " with some padding text to make it heavier");
        return n;
    }

    private static HierarchySnapshotDoc doc(int parents, int childrenEach) {
        HierarchySnapshotDoc d = HierarchySnapshotDoc.building("p1", "r1");
        List<OntologyDto.TreeNode> top = new ArrayList<>();
        Map<String, List<OntologyDto.TreeNode>> index = new LinkedHashMap<>();
        for (int i = 0; i < parents; i++) {
            String parent = "http://example.org/c#P" + i;
            top.add(node(parent));
            List<OntologyDto.TreeNode> kids = new ArrayList<>();
            for (int j = 0; j < childrenEach; j++) {
                kids.add(node(parent + "_" + j));
            }
            index.put(parent, kids);
        }
        d.setTopLevelNodes(top);
        d.setTopLevelTotal(top.size());
        d.setChildrenByParent(index);
        d.setMeta(Map.of("classCount", 7, "hierarchyEngine", "snapshot"));
        d.markReady();
        return d;
    }

    private static Map<String, List<String>> ids(Map<String, List<OntologyDto.TreeNode>> index) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        index.forEach((k, v) -> out.put(k, v.stream().map(OntologyDto.TreeNode::getId).toList()));
        return out;
    }

    @Test
    void aLargeSnapshotIsStoredInOneRowAndReadBackIdentically() {
        HierarchySnapshotDoc original = doc(400, 12);
        Map<String, List<String>> expected = ids(original.getChildrenByParent());

        store.save(original);

        assertEquals(1, jdbc.queryForObject("select count(*) from hierarchy_snapshot", Integer.class));
        HierarchySnapshotDoc read = store.findById("p1").orElseThrow();
        assertEquals(HierarchySnapshotDoc.Status.READY, read.getStatus());
        assertEquals(400, read.getTopLevelNodes().size());
        assertEquals(400, read.getTopLevelTotal());
        assertEquals("r1", read.getRevision());
        assertEquals(7, read.getMeta().get("classCount"));
        assertEquals(expected, ids(read.getChildrenByParent()));
        assertTrue(read.getExpiresAt().after(new Date()));
    }

    @Test
    void classIrisWithDotsColonsAndSlashesSurviveAsMapKeys() {
        HierarchySnapshotDoc d = doc(0, 0);
        Map<String, List<OntologyDto.TreeNode>> index = new LinkedHashMap<>();
        index.put("http://example.org/a.b:c/d#E", List.of(node("http://example.org/kid")));
        d.setChildrenByParent(index);
        d.setTopLevelNodes(List.of());

        store.save(d);

        assertEquals(List.of("http://example.org/kid"),
                ids(store.findById("p1").orElseThrow().getChildrenByParent()).get("http://example.org/a.b:c/d#E"));
    }

    @Test
    void aBuildingRowHasNoPayloadAndNoExpiry() {
        store.save(HierarchySnapshotDoc.building("p1", "r2"));

        HierarchySnapshotDoc read = store.findById("p1").orElseThrow();
        assertEquals(HierarchySnapshotDoc.Status.BUILDING, read.getStatus());
        assertNull(read.getTopLevelNodes());
        assertNull(read.getExpiresAt());
    }

    @Test
    void markingAStoredSnapshotStaleKeepsTheHierarchy() {
        store.save(doc(5, 3));
        HierarchySnapshotDoc raw = store.findRaw("p1").orElseThrow();
        assertNull(raw.getTopLevelNodes());
        raw.setStatus(HierarchySnapshotDoc.Status.STALE);

        store.save(raw);

        HierarchySnapshotDoc read = store.findById("p1").orElseThrow();
        assertEquals(HierarchySnapshotDoc.Status.STALE, read.getStatus());
        assertEquals(5, read.getTopLevelNodes().size());
        assertEquals(3, read.getChildrenByParent().get("http://example.org/c#P2").size());
    }

    @Test
    void resavingReplacesTheSnapshotInPlace() {
        store.save(doc(40, 6));
        store.save(doc(2, 1));

        assertEquals(1, jdbc.queryForObject("select count(*) from hierarchy_snapshot", Integer.class));
        assertEquals(2, store.findById("p1").orElseThrow().getTopLevelNodes().size());
    }

    @Test
    void anExpiredSnapshotIsIgnored() {
        store.save(doc(5, 3));
        jdbc.update("update hierarchy_snapshot set expires_at = now() - interval '1 minute'");

        assertTrue(store.findById("p1").isEmpty());
        assertTrue(store.findRaw("p1").isEmpty());
    }

    @Test
    void aCorruptStoredPayloadIsTreatedAsNoSnapshot() {
        store.save(doc(5, 3));
        jdbc.update("update hierarchy_snapshot set payload = 'not json'");

        assertTrue(store.findById("p1").isEmpty());
    }

    @Test
    void aSnapshotBeyondTheSizeCapIsRefusedAndNothingIsStored() {
        ReflectionTestUtils.setField(store, "maxPayloadBytes", 1_000);

        assertThrows(HierarchySnapshotStore.SnapshotTooLargeException.class, () -> store.save(doc(200, 6)));
        assertEquals(0, jdbc.queryForObject("select count(*) from hierarchy_snapshot", Integer.class));
    }

    @Test
    void deleteRemovesTheSnapshot() {
        store.save(doc(5, 3));

        store.deleteById("p1");

        assertTrue(store.findById("p1").isEmpty());
    }
}
