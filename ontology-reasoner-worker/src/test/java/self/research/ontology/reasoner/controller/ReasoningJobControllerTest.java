package self.research.ontology.reasoner.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import self.research.ontology.reasoner.model.ReasoningJob;
import self.research.ontology.reasoner.service.ReasoningQueueManager;
import self.research.ontology.reasoner.service.ReasoningQueueProcessor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReasoningJobControllerTest {

    private ReasoningQueueManager queueManager;
    private ReasoningQueueProcessor queueProcessor;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        queueManager = mock(ReasoningQueueManager.class);
        queueProcessor = mock(ReasoningQueueProcessor.class);
        mvc = MockMvcBuilders.standaloneSetup(new ReasoningJobController(queueManager, queueProcessor)).build();
    }

    @Test
    void submittingPastTheProjectLimitReturnsTooManyRequests() throws Exception {
        when(queueManager.enqueue(any(), eq("P1"), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ReasoningQueueManager.ProjectQueueFullException("This project already has 5 reasoning jobs waiting"));

        mvc.perform(post("/api/reasoning/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"REASONER_CLASSIFY\",\"projectId\":\"P1\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("This project already has 5 reasoning jobs waiting"));
    }

    @Test
    void cancellingAnUnknownJobReturnsNotFound() throws Exception {
        when(queueProcessor.cancel("missing")).thenReturn(
                new ReasoningQueueManager.CancelResult(ReasoningQueueManager.CancelOutcome.NOT_FOUND, null));

        mvc.perform(delete("/api/reasoning/jobs/missing")).andExpect(status().isNotFound());
    }

    @Test
    void cancellingAQueuedJobReturnsItsFailedState() throws Exception {
        ReasoningJob job = ReasoningJob.builder()
                .jobId("job-1")
                .jobType(ReasoningJob.JobType.REASONER_CLASSIFY)
                .projectId("P1")
                .status(ReasoningJob.Status.FAILED)
                .error("Cancelled")
                .build();
        when(queueProcessor.cancel("job-1")).thenReturn(
                new ReasoningQueueManager.CancelResult(ReasoningQueueManager.CancelOutcome.CANCELLED_QUEUED, job));

        mvc.perform(delete("/api/reasoning/jobs/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled").value(true))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error").value("Cancelled"));
    }
}
