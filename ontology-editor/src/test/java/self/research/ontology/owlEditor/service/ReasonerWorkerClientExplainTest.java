package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ReasonerWorkerClientExplainTest {

    private ReasonerWorkerClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        client = new ReasonerWorkerClient("http://worker:8088", "secret");
        server = MockRestServiceServer.bindTo((RestTemplate) ReflectionTestUtils.getField(client, "restTemplate")).build();
    }

    @Test
    void explainJobIsSentWithTheFactFieldsAndTheInternalToken() {
        server.expect(requestTo("http://worker:8088/api/reasoning/jobs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Ontocode-Internal-Token", "secret"))
                .andExpect(jsonPath("$.jobType").value("EXPLAIN_INFERENCE"))
                .andExpect(jsonPath("$.projectId").value("p1"))
                .andExpect(jsonPath("$.entailmentType").value("DataPropertyAssertion"))
                .andExpect(jsonPath("$.subjectIri").value("urn:s"))
                .andExpect(jsonPath("$.propertyIri").value("urn:p"))
                .andExpect(jsonPath("$.literalValue").value("41"))
                .andExpect(jsonPath("$.params.literalDatatypeIri").value("urn:dt"))
                .andExpect(jsonPath("$.reasonerType").value("ELK"))
                .andExpect(jsonPath("$.ownerEmail").value("user-1"))
                .andRespond(withSuccess("{\"success\":true,\"jobId\":\"e1\",\"status\":\"QUEUED\"}", MediaType.APPLICATION_JSON));

        Map<String, Object> result = client.submitExplainInference("p1",
                new ExplainInferenceRequest("DataPropertyAssertion", "urn:s", "urn:p", null, "41", "urn:dt", null, null, null),
                "ELK", "user-1");

        assertEquals("e1", result.get("jobId"));
        server.verify();
    }

    @Test
    void unionOperandsGoInTheParamsAndPublishedRequestsCarryNoOwner() {
        server.expect(requestTo("http://worker:8088/api/reasoning/jobs"))
                .andExpect(jsonPath("$.params.objectIris[0]").value("urn:A"))
                .andExpect(jsonPath("$.params.objectIris[1]").value("urn:B"))
                .andExpect(jsonPath("$.params.objectOperator").value("UNION"))
                .andExpect(jsonPath("$.ownerEmail").doesNotExist())
                .andExpect(jsonPath("$.targetIri").doesNotExist())
                .andRespond(withSuccess("{\"success\":true,\"jobId\":\"e2\"}", MediaType.APPLICATION_JSON));

        client.submitExplainInference("p1",
                new ExplainInferenceRequest("ClassAssertion", "urn:s", null, null, null, null, List.of("urn:A", "urn:B"), "UNION", null),
                "HERMIT", null);

        server.verify();
    }

    @Test
    void queueFullFromTheWorkerIsReportedAsRateLimited() {
        server.expect(requestTo("http://worker:8088/api/reasoning/jobs"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"error\":\"5 reasoning jobs are already queued.\"}"));

        Map<String, Object> result = client.submitExplainInference("p1",
                new ExplainInferenceRequest("ClassAssertion", "urn:s", null, "urn:C", null, null, null, null, null),
                "HERMIT", null);

        assertEquals(true, result.get("rateLimited"));
        assertEquals("5 reasoning jobs are already queued.", result.get("error"));
    }
}
