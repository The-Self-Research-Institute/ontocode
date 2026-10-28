package self.research.ontology.swrl.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import self.research.ontology.swrl.service.SqwrlResultStore;
import self.research.ontology.swrl.service.SwrlBusyException;
import self.research.ontology.swrl.service.SwrlEngineService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class SqwrlPagingControllerTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private MutableClock clock;
    private SwrlEngineService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        service = Mockito.mock(SwrlEngineService.class);
        SwrlController controller = new SwrlController();
        ReflectionTestUtils.setField(controller, "swrlEngineService", service);
        ReflectionTestUtils.setField(controller, "sqwrlResults", new SqwrlResultStore(clock, Duration.ofMinutes(15), 5, 50, 64L << 20));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private void answerWithRows(int stored, int total, boolean exact) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < stored; i++) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("p", "person" + (i + 1));
            row.put("age", String.valueOf((i * 7) % 90));
            rows.add(row);
        }
        Mockito.when(service.executeSqwrlQuery(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any()))
                .thenReturn(new SwrlEngineService.SqwrlQueryResult(true, "q", "x", 5, List.of("p", "age"), rows, null,
                        total, exact, total > stored, stored, false));
    }

    private JsonNode run(String body) throws Exception {
        String json = mvc.perform(post("/api/swrl/p1/sqwrl/query").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json);
    }

    @Test
    void theFirstPageDefaultsToOneHundredRowsAndCarriesARunIdAndTheTotals() throws Exception {
        answerWithRows(1000, 5000, true);
        JsonNode body = run("{\"queryText\":\"x\"}");
        assertEquals(100, body.get("rows").size());
        assertEquals(100, body.get("rowCount").asInt());
        assertEquals(1000, body.get("storedRows").asInt());
        assertEquals(5000, body.get("totalRows").asInt());
        assertTrue(body.get("truncated").asBoolean());
        assertFalse(body.get("runId").asText().isBlank());
    }

    @Test
    void thePageSizeIsHonouredAndClampedToTheHardMaximum() throws Exception {
        answerWithRows(1500, 1500, true);
        assertEquals(25, run("{\"queryText\":\"x\",\"pageSize\":25}").get("rows").size());
        assertEquals(1000, run("{\"queryText\":\"x\",\"pageSize\":99999}").get("rows").size());
    }

    @Test
    void laterPagesFilterAndSortOnTheServer() throws Exception {
        answerWithRows(300, 300, true);
        String runId = run("{\"queryText\":\"x\"}").get("runId").asText();

        JsonNode second = mapper.readTree(mvc.perform(get("/api/swrl/p1/sqwrl/runs/" + runId + "/rows?offset=100&limit=100"))
                .andReturn().getResponse().getContentAsString());
        assertEquals(100, second.get("rows").size());
        assertEquals("person101", second.get("rows").get(0).get("p").asText());
        assertEquals(300, second.get("filteredRows").asInt());

        JsonNode filtered = mapper.readTree(mvc.perform(get("/api/swrl/p1/sqwrl/runs/" + runId + "/rows?filter=PERSON29"))
                .andReturn().getResponse().getContentAsString());
        assertEquals(11, filtered.get("filteredRows").asInt());

        JsonNode sorted = mapper.readTree(mvc.perform(get("/api/swrl/p1/sqwrl/runs/" + runId + "/rows?sort=age&dir=desc&limit=3"))
                .andReturn().getResponse().getContentAsString());
        int first = sorted.get("rows").get(0).get("age").asInt();
        int next = sorted.get("rows").get(1).get("age").asInt();
        assertTrue(first >= next);
    }

    @Test
    void aPageRequestAboveTheMaximumIsClampedAndExportReturnsTheWholeStoredResult() throws Exception {
        answerWithRows(1200, 1200, true);
        String runId = run("{\"queryText\":\"x\"}").get("runId").asText();
        JsonNode page = mapper.readTree(mvc.perform(get("/api/swrl/p1/sqwrl/runs/" + runId + "/rows?limit=50000"))
                .andReturn().getResponse().getContentAsString());
        assertEquals(1000, page.get("rows").size());
        JsonNode export = mapper.readTree(mvc.perform(get("/api/swrl/p1/sqwrl/runs/" + runId + "/rows?export=true"))
                .andReturn().getResponse().getContentAsString());
        assertEquals(1200, export.get("rows").size());
    }

    @Test
    void anExpiredOrUnknownRunAnswers410WithAPlainMessage() throws Exception {
        answerWithRows(10, 10, true);
        String runId = run("{\"queryText\":\"x\"}").get("runId").asText();
        clock.now = clock.now.plus(Duration.ofMinutes(16));
        var result = mvc.perform(get("/api/swrl/p1/sqwrl/runs/" + runId + "/rows")).andReturn().getResponse();
        assertEquals(410, result.getStatus());
        assertTrue(result.getContentAsString().contains("This result has expired. Run the query again."));
        assertEquals(410, mvc.perform(get("/api/swrl/p1/sqwrl/runs/nope/rows")).andReturn().getResponse().getStatus());
    }

    @Test
    void aBusyEngineAnswers429WithTheSharedBusyMessage() throws Exception {
        Mockito.when(service.executeSqwrlQuery(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any()))
                .thenThrow(new SwrlBusyException("queue_full", 5, "The rule engine is busy. Try again in a moment."));
        var response = mvc.perform(post("/api/swrl/p1/sqwrl/query").contentType(MediaType.APPLICATION_JSON).content("{\"queryText\":\"x\"}"))
                .andReturn().getResponse();
        assertEquals(429, response.getStatus());
        assertEquals("5", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("The rule engine is busy. Try again in a moment."));
    }
}
