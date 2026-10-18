package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.DraftCopyStatus;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;
import self.research.ontology.owlEditor.model.collaboration.QueueStatusMessage;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DraftCopyServiceTest {

    private static final String P = "proj";
    private static final String U = "u@x.org";

    private SparqlDatasetService datasets;
    private DraftSessionRepository sessions;
    private DraftCopyService service;
    private DraftSession stored;

    @BeforeEach
    void setUp() {
        datasets = mock(SparqlDatasetService.class);
        sessions = mock(DraftSessionRepository.class);
        MainGraphRevisionService revisions = mock(MainGraphRevisionService.class);
        ImportQueueManager queue = mock(ImportQueueManager.class);
        when(queue.getQueueStats()).thenReturn(QueueStatusMessage.QueueStats.builder().build());
        service = new DraftCopyService(datasets, sessions, revisions, queue, mock(DraftPublishMergeService.class));
        when(sessions.findByProjectIdAndUserId(anyString(), anyString())).thenAnswer(call -> Optional.ofNullable(stored));
        when(sessions.save(any(DraftSession.class))).thenAnswer(call -> {
            stored = call.getArgument(0);
            return stored;
        });
    }

    @Test
    void anEmptyFileIsReadyAtOnceWithoutCopying() {
        when(datasets.countMainGraphTriples(P)).thenReturn(0L);

        DraftCopyService.InitiateResult result = service.initiateCopy(P, U);

        assertTrue(result.accepted());
        assertEquals(DraftCopyStatus.READY, service.getStatus(P, U));
        verify(datasets, never()).copyMainGraphToDraft(anyString(), anyString());
    }
}
