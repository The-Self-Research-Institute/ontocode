package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.document.HierarchySnapshotChunkDoc;
import self.research.ontology.owlEditor.document.HierarchySnapshotDoc;
import self.research.ontology.owlEditor.dto.OntologyDto;
import self.research.ontology.owlEditor.repository.HierarchySnapshotChunkRepository;
import self.research.ontology.owlEditor.repository.HierarchySnapshotRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HierarchySnapshotStoreTest {

    private final Map<String, HierarchySnapshotDoc> docs = new HashMap<>();
    private final Map<String, HierarchySnapshotChunkDoc> chunks = new LinkedHashMap<>();
    private HierarchySnapshotStore store;

    @BeforeEach
    void setUp() {
        HierarchySnapshotRepository repository = mock(HierarchySnapshotRepository.class);
        when(repository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(docs.get(inv.<String>getArgument(0))));
        when(repository.save(any())).thenAnswer(inv -> {
            HierarchySnapshotDoc d = inv.getArgument(0);
            docs.put(d.getProjectId(), d);
            return d;
        });
        HierarchySnapshotChunkRepository chunkRepository = mock(HierarchySnapshotChunkRepository.class);
        when(chunkRepository.save(any())).thenAnswer(inv -> {
            HierarchySnapshotChunkDoc c = inv.getArgument(0);
            chunks.put(c.getId(), c);
            return c;
        });
        when(chunkRepository.findBySetIdOrderByIndexAsc(anyString())).thenAnswer(inv -> {
            String setId = inv.getArgument(0);
            List<HierarchySnapshotChunkDoc> found = new ArrayList<>();
            chunks.values().stream().filter(c -> c.getSetId().equals(setId)).forEach(found::add);
            found.sort((a, b) -> Integer.compare(a.getIndex(), b.getIndex()));
            return found;
        });
        doAnswer(inv -> {
            String setId = inv.getArgument(0);
            chunks.values().removeIf(c -> c.getSetId().equals(setId));
            return null;
        }).when(chunkRepository).deleteBySetId(anyString());
        store = new HierarchySnapshotStore(repository, chunkRepository);
    }

    private void shrinkLimits() {
        ReflectionTestUtils.setField(store, "inlineLimitBytes", 1_000);
        ReflectionTestUtils.setField(store, "chunkBytes", 300);
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
        d.markReady();
        return d;
    }

    private static Map<String, List<String>> ids(Map<String, List<OntologyDto.TreeNode>> index) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        index.forEach((k, v) -> out.put(k, v.stream().map(OntologyDto.TreeNode::getId).toList()));
        return out;
    }

    @Test
    void smallSnapshotIsStoredAsOneDocument() {
        store.save(doc(5, 3));

        assertTrue(chunks.isEmpty());
        assertNull(docs.get("p1").getChunkSetId());
        HierarchySnapshotDoc read = store.findById("p1").orElseThrow();
        assertEquals(5, read.getTopLevelNodes().size());
        assertEquals(3, read.getChildrenByParent().get("http://example.org/c#P2").size());
    }

    @Test
    void largeSnapshotIsChunkedAndReassembledIdentically() {
        shrinkLimits();
        HierarchySnapshotDoc original = doc(40, 6);
        Map<String, List<String>> expected = ids(original.getChildrenByParent());

        store.save(original);

        assertTrue(chunks.size() > 1);
        assertNull(docs.get("p1").getChildrenByParent());
        HierarchySnapshotDoc read = store.findById("p1").orElseThrow();
        assertEquals(HierarchySnapshotDoc.Status.READY, read.getStatus());
        assertEquals(40, read.getTopLevelNodes().size());
        assertEquals(40, read.getTopLevelTotal());
        assertEquals(expected, ids(read.getChildrenByParent()));
    }

    @Test
    void missingChunkIsTreatedAsNoSnapshot() {
        shrinkLimits();
        store.save(doc(40, 6));
        chunks.remove(chunks.keySet().iterator().next());

        assertTrue(store.findById("p1").isEmpty());
    }

    @Test
    void corruptChunkIsTreatedAsNoSnapshot() {
        shrinkLimits();
        store.save(doc(40, 6));
        chunks.values().iterator().next().setData(new byte[] {1, 2, 3});

        assertTrue(store.findById("p1").isEmpty());
    }

    @Test
    void resavingReplacesOldChunks() {
        shrinkLimits();
        store.save(doc(40, 6));
        store.save(doc(40, 6));
        int afterTwo = chunks.size();
        store.save(doc(1, 0));

        assertTrue(afterTwo > 1);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void snapshotBeyondTheChunkCapIsRefused() {
        ReflectionTestUtils.setField(store, "inlineLimitBytes", 1_000);
        ReflectionTestUtils.setField(store, "chunkBytes", 10);

        assertThrows(HierarchySnapshotStore.SnapshotTooLargeException.class, () -> store.save(doc(200, 6)));
        assertTrue(chunks.isEmpty());
        assertTrue(docs.isEmpty());
    }
}
