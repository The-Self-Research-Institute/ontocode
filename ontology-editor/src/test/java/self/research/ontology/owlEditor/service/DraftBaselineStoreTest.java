package self.research.ontology.owlEditor.service;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DraftBaselineStoreTest {

    private final GridFsTemplate gridFs = mock(GridFsTemplate.class);
    private final DraftBaselineStore store = new DraftBaselineStore(gridFs);

    @Test
    void savingReplacesAnyEarlierCopyForTheSameProjectAndUser() {
        store.save("p1", "u1", "<rdf/>");

        InOrder order = inOrder(gridFs);
        ArgumentCaptor<Query> removed = ArgumentCaptor.forClass(Query.class);
        order.verify(gridFs).delete(removed.capture());
        order.verify(gridFs).store(any(InputStream.class), eq("draft-baseline/p1/u1"), eq("application/rdf+xml"),
                eq(Map.of("type", "draft-baseline", "projectId", "p1", "userId", "u1")));
        assertEquals("draft-baseline/p1/u1", removed.getValue().getQueryObject().get("filename"));
    }

    @Test
    void loadingReturnsWhatWasSaved() throws Exception {
        GridFSFile file = mock(GridFSFile.class);
        when(gridFs.findOne(any(Query.class))).thenReturn(file);
        GridFsResource resource = new GridFsResource(file,
                new ByteArrayResource("<rdf/>".getBytes(StandardCharsets.UTF_8)).getInputStream());
        when(gridFs.getResource(file)).thenReturn(resource);

        assertEquals(Optional.of("<rdf/>"), store.load("p1", "u1"));
    }

    @Test
    void loadingReturnsNothingWhenThereIsNoCopy() {
        when(gridFs.findOne(any(Query.class))).thenReturn(null);

        assertTrue(store.load("p1", "u1").isEmpty());
    }

    @Test
    void deletingRemovesOnlyThatUsersCopy() {
        store.delete("p1", "u1");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(gridFs).delete(query.capture());
        assertEquals("draft-baseline/p1/u1", query.getValue().getQueryObject().get("filename"));
    }
}
