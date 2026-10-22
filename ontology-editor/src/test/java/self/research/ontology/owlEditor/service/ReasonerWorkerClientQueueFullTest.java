package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class ReasonerWorkerClientQueueFullTest {

    @Test
    void queueFullFromTheWorkerIsReportedAsRateLimitedWithItsMessage() {
        ReasonerWorkerClient client = new ReasonerWorkerClient("http://worker:8088", "token");
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://worker:8088/api/reasoning/jobs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"error\":\"5 reasoning jobs are already queued for this project.\"}"));

        Map<String, Object> result = client.submitJob("REASONER_HIERARCHY", "proj-1", null, null, "HERMIT", null);

        assertEquals(false, result.get("success"));
        assertEquals(true, result.get("rateLimited"));
        assertEquals("5 reasoning jobs are already queued for this project.", result.get("error"));
        server.verify();
    }

    @Test
    void queueFullWithoutABodyStillGivesAClearMessage() {
        ReasonerWorkerClient client = new ReasonerWorkerClient("http://worker:8088", "token");
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://worker:8088/api/reasoning/jobs"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        Map<String, Object> result = client.submitJob("REASONER_HIERARCHY", "proj-1", null, null, "HERMIT", null);

        assertEquals(true, result.get("rateLimited"));
        assertEquals("Too many reasoning jobs are already queued for this project. Please wait for them to finish and try again.",
                result.get("error"));
    }
}
