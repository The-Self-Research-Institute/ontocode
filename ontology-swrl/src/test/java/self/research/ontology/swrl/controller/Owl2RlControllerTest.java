package self.research.ontology.swrl.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import self.research.ontology.common.preview.PreviewStamp;
import self.research.ontology.swrl.model.InferredAxiom;
import self.research.ontology.swrl.service.OwnerContext;
import self.research.ontology.swrl.service.SqwrlResultStore;
import self.research.ontology.swrl.service.SwrlBusyException;
import self.research.ontology.swrl.service.SwrlEngineService;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class Owl2RlControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private SwrlEngineService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(SwrlEngineService.class);
        SwrlController controller = new SwrlController();
        ReflectionTestUtils.setField(controller, "swrlEngineService", service);
        ReflectionTestUtils.setField(controller, "sqwrlResults", new SqwrlResultStore(Clock.systemUTC(), Duration.ofMinutes(15), 5, 50, 64L << 20));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static InferredAxiom axiom(String type, String text, String category) {
        InferredAxiom a = new InferredAxiom(type, text, text);
        a.setCategory(category);
        return a;
    }

    private static SwrlEngineService.Owl2RlResult found(int classAssertions, int subclasses, boolean truncated) {
        List<InferredAxiom> axioms = new ArrayList<>();
        for (int i = 0; i < classAssertions; i++) {
            axioms.add(axiom("ClassAssertion", "ClassAssertion(Vehicle car" + i + ")", "conclusion"));
        }
        for (int i = 0; i < subclasses; i++) {
            axioms.add(axiom("SubClassOf", "SubClassOf(Sub" + i + " Vehicle)", "structure"));
        }
        return new SwrlEngineService.Owl2RlResult(true, "completed", null, 12, axioms.size() + (truncated ? 40 : 0), truncated, 5000, 3,
                Map.of("ClassAssertion", classAssertions, "SubClassOf", subclasses), axioms, new PreviewStamp(4L, null, null), List.of("prp-spo2"));
    }

    private JsonNode run(String query) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/swrl/p1/owl2rl" + query)).andReturn().getResponse().getContentAsString());
    }

    private JsonNode fetchRun(String path) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/swrl/p1/owl2rl/runs/" + path)).andReturn().getResponse().getContentAsString());
    }

    @Test
    void theFirstPageCarriesTheCountsTheTruncationFlagAndARunId() throws Exception {
        Mockito.when(service.deriveOwl2Rl("p1", null)).thenReturn(found(150, 20, true));

        JsonNode body = run("");

        assertTrue(body.get("success").asBoolean());
        assertEquals(100, body.get("axioms").size());
        assertEquals(100, body.get("pageSize").asInt());
        assertEquals(170, body.get("returnedCount").asInt());
        assertEquals(210, body.get("derivedCount").asInt());
        assertTrue(body.get("truncated").asBoolean());
        assertEquals(5000, body.get("maxAxioms").asInt());
        assertEquals(170, body.get("filteredCount").asInt());
        assertEquals(2, body.get("totalPages").asInt());
        assertEquals(150, body.get("typeCounts").get("ClassAssertion").asInt());
        assertEquals("prp-spo2", body.get("unsupportedRules").get(0).asText());
        assertEquals(4, body.get("stamp").get("ontologyRevision").asInt());
        assertFalse(body.get("runId").asText().isBlank());
    }

    @Test
    void thePageSizeIsHonouredAndCappedAtTheMaximum() throws Exception {
        Mockito.when(service.deriveOwl2Rl("p1", null)).thenReturn(found(900, 0, false));

        assertEquals(25, run("?pageSize=25").get("axioms").size());
        JsonNode capped = run("?pageSize=99999");
        assertEquals(500, capped.get("axioms").size());
        assertEquals(500, capped.get("pageSize").asInt());
    }

    @Test
    void laterPagesFilterByTypeAndTextOnTheServerWithoutRunningTheEngineAgain() throws Exception {
        Mockito.when(service.deriveOwl2Rl("p1", null)).thenReturn(found(130, 30, false));
        String runId = run("").get("runId").asText();

        JsonNode second = fetchRun(runId + "?page=1&pageSize=100");
        assertEquals(60, second.get("axioms").size());
        assertEquals(160, second.get("filteredCount").asInt());

        JsonNode byType = fetchRun(runId + "?type=SubClassOf&pageSize=10");
        assertEquals(10, byType.get("axioms").size());
        assertEquals(30, byType.get("filteredCount").asInt());
        assertEquals(3, byType.get("totalPages").asInt());
        assertTrue(byType.get("axioms").findValues("axiomType").stream().allMatch(t -> t.asText().equals("SubClassOf")));

        JsonNode byText = fetchRun(runId + "?filter=car12");
        assertEquals(11, byText.get("filteredCount").asInt());

        Mockito.verify(service, Mockito.times(1)).deriveOwl2Rl("p1", null);
    }

    @Test
    void aRunOfAnotherProjectOrAnUnknownRunIsGone() throws Exception {
        Mockito.when(service.deriveOwl2Rl("p1", null)).thenReturn(found(5, 0, false));
        String runId = run("").get("runId").asText();

        assertEquals(410, mvc.perform(get("/api/swrl/p2/owl2rl/runs/" + runId)).andReturn().getResponse().getStatus());
        assertEquals(410, mvc.perform(get("/api/swrl/p1/owl2rl/runs/nope")).andReturn().getResponse().getStatus());
    }

    @Test
    void aRunOfAnotherOwnerIsGone() throws Exception {
        Mockito.when(service.deriveOwl2Rl("p1", null)).thenReturn(found(5, 0, false));
        OwnerContext.set("owner-a");
        String runId;
        try {
            runId = run("").get("runId").asText();
        } finally {
            OwnerContext.clear();
        }
        OwnerContext.set("owner-b");
        try {
            assertEquals(410, mvc.perform(get("/api/swrl/p1/owl2rl/runs/" + runId)).andReturn().getResponse().getStatus());
        } finally {
            OwnerContext.clear();
        }
    }

    @Test
    void aDraftRunNeedsAUserIdAndPassesItOn() throws Exception {
        assertEquals(400, mvc.perform(post("/api/swrl/p1/owl2rl?draft=true")).andReturn().getResponse().getStatus());
        Mockito.verifyNoInteractions(service);

        Mockito.when(service.deriveOwl2Rl("p1", "u7")).thenReturn(found(2, 0, false));
        assertTrue(run("?draft=true&userId=u7").get("success").asBoolean());
        Mockito.verify(service).deriveOwl2Rl("p1", "u7");
    }

    @Test
    void aBusyGateAnswers429WithTheBusyBody() throws Exception {
        Mockito.when(service.deriveOwl2Rl("p1", null))
                .thenThrow(new SwrlBusyException("queue_full", 5, "The rule engine is busy. Try again in a moment."));

        var response = mvc.perform(post("/api/swrl/p1/owl2rl")).andReturn().getResponse();

        assertEquals(429, response.getStatus());
        assertEquals("5", response.getHeader("Retry-After"));
        JsonNode body = mapper.readTree(response.getContentAsString());
        assertTrue(body.get("busy").asBoolean());
        assertEquals("queue_full", body.get("reason").asText());
    }

    @Test
    void aTimeoutIsReportedAsAFailedRunWithNoAxiomsAndNoRunId() throws Exception {
        Mockito.when(service.deriveOwl2Rl("p1", null)).thenReturn(new SwrlEngineService.Owl2RlResult(false, "timeout",
                "Execution timed out after 150 seconds", 0, 0, false, 5000, 0, Map.of(), List.of(), null, List.of()));

        JsonNode body = run("");

        assertFalse(body.get("success").asBoolean());
        assertEquals("timeout", body.get("completion").asText());
        assertEquals(0, body.get("axioms").size());
        assertTrue(body.get("runId").isNull());
    }
}
