package self.research.ontology.reasoner.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import self.research.ontology.reasoner.model.ReasoningJob;
import self.research.ontology.reasoner.service.ReasoningQueueManager;
import self.research.ontology.reasoner.service.ReasoningQueueProcessor;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExplainInferenceControllerTest {

    private ReasoningQueueManager queueManager;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        queueManager = mock(ReasoningQueueManager.class);
        ReasoningQueueProcessor queueProcessor = mock(ReasoningQueueProcessor.class);
        mvc = MockMvcBuilders.standaloneSetup(new ReasoningJobController(queueManager, queueProcessor)).build();
    }

    @Test
    void explainSubmitPassesTheFactAndTheDatatypeThroughAndIsAccepted() throws Exception {
        ReasoningJob job = ReasoningJob.builder()
                .jobId("e1")
                .jobType(ReasoningJob.JobType.EXPLAIN_INFERENCE)
                .projectId("P1")
                .status(ReasoningJob.Status.QUEUED)
                .queuePosition(1)
                .build();
        when(queueManager.enqueue(any(), eq("P1"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any())).thenReturn(job);

        mvc.perform(post("/api/reasoning/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"EXPLAIN_INFERENCE\",\"projectId\":\"P1\",\"reasonerType\":\"HERMIT\","
                                + "\"ownerEmail\":\"user-1\",\"entailmentType\":\"DataPropertyAssertion\","
                                + "\"subjectIri\":\"urn:s\",\"propertyIri\":\"urn:p\",\"literalValue\":\"41\","
                                + "\"params\":{\"literalDatatypeIri\":\"urn:dt\"}}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.jobId").value("e1"))
                .andExpect(jsonPath("$.jobType").value("EXPLAIN_INFERENCE"))
                .andExpect(jsonPath("$.status").value("QUEUED"));

        verify(queueManager).enqueue(eq(ReasoningJob.JobType.EXPLAIN_INFERENCE), eq("P1"), any(), any(), eq("HERMIT"),
                eq("user-1"), any(), any(), eq("DataPropertyAssertion"), eq("urn:s"), any(), eq("urn:p"),
                eq("41"), eq(Map.of("literalDatatypeIri", "urn:dt")));
    }

    @Test
    void explainSubmitPastTheProjectLimitIsTooManyRequests() throws Exception {
        when(queueManager.enqueue(any(), eq("P1"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenThrow(new ReasoningQueueManager.ProjectQueueFullException("This project already has 5 reasoning jobs waiting"));

        mvc.perform(post("/api/reasoning/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"EXPLAIN_INFERENCE\",\"projectId\":\"P1\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void finishedExplainJobExposesItsResultFieldsAtTheTopLevel() throws Exception {
        ReasoningJob job = ReasoningJob.builder()
                .jobId("e2")
                .jobType(ReasoningJob.JobType.EXPLAIN_INFERENCE)
                .projectId("P1")
                .status(ReasoningJob.Status.COMPLETED)
                .result(Map.of("success", true, "explainStatus", "EXPLAINED", "axiomCount", 3))
                .build();
        when(queueManager.getJob("e2")).thenReturn(job);

        mvc.perform(get("/api/reasoning/jobs/e2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.explainStatus").value("EXPLAINED"))
                .andExpect(jsonPath("$.axiomCount").value(3));
    }
}
