package self.research.ontology.plugins.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class ReasonerWorkerClientQueueFullTest {

    @Test
    void queueFullFromTheWorkerIsReportedAsRateLimitedWithItsMessage() {
        ReasonerWorkerClient client = new ReasonerWorkerClient("http://worker:8088", "token");
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://worker:8088/api/reasoning/jobs"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"error\":\"5 reasoning jobs are already queued for this project.\"}"));

        Map<String, Object> result = client.submit("REASONER_CONSISTENCY", "proj-1", "HERMIT");

        assertEquals(false, result.get("success"));
        assertEquals(true, result.get("rateLimited"));
        assertEquals("5 reasoning jobs are already queued for this project.", result.get("error"));
        server.verify();
    }
}
