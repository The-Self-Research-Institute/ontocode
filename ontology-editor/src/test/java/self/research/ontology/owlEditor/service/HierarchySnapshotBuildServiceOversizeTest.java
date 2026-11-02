package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.document.HierarchySnapshotDoc;
import self.research.ontology.owlEditor.dto.OntologyDto;
import self.research.ontology.owlEditor.hierarchy.HierarchySnapshotBuilder;
import self.research.ontology.owlEditor.hierarchy.OntologyMetricsComputer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HierarchySnapshotBuildServiceOversizeTest {

    @Test
    void oversizeSnapshotIsMarkedFailedWithAReason() throws Exception {
        Path dir = Files.createTempDirectory("hier-oversize");
        Files.writeString(dir.resolve("ontology.original.ttl"),
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n<http://e.org/A> a owl:Class .\n");
        StorageManager storage = mock(StorageManager.class);
        when(storage.projectDir(anyString())).thenReturn(dir);
        HierarchySnapshotBuilder builder = mock(HierarchySnapshotBuilder.class);
        when(builder.countTopLevelCandidates(any(), any())).thenReturn(1);
        when(builder.buildTopLevel(any(), any(), anyInt(), anyInt())).thenReturn(List.<OntologyDto.TreeNode>of());
        when(builder.buildChildrenIndex(any(), any())).thenReturn(Map.of());
        OntologyMetricsComputer metrics = mock(OntologyMetricsComputer.class);
        when(metrics.compute(any(), any())).thenReturn(Map.of());
        ProjectMetadataService meta = mock(ProjectMetadataService.class);
        when(meta.readMeta(anyString())).thenReturn(Optional.empty());

        HierarchySnapshotDoc[] stored = new HierarchySnapshotDoc[1];
        HierarchySnapshotStore store = mock(HierarchySnapshotStore.class);
        when(store.findRaw(anyString())).thenAnswer(inv -> Optional.ofNullable(stored[0]));
        doAnswer(inv -> {
            HierarchySnapshotDoc d = inv.getArgument(0);
            if (d.getStatus() == HierarchySnapshotDoc.Status.READY) {
                throw new HierarchySnapshotStore.SnapshotTooLargeException("Payload document size is larger than maximum");
            }
            stored[0] = d;
            return d;
        }).when(store).save(any());

        HierarchySnapshotBuildService service =
                new HierarchySnapshotBuildService(storage, builder, metrics, store, meta);
        ReflectionTestUtils.setField(service, "snapshotEnabled", true);

        service.buildAndStore("p1", "r1");

        assertEquals(HierarchySnapshotDoc.Status.FAILED, stored[0].getStatus());
        assertTrue(stored[0].getErrorMessage().contains("too large"));
    }
}
