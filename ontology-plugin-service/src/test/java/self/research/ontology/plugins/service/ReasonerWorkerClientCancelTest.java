package self.research.ontology.plugins.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ReasonerWorkerClientCancelTest {

    @Test
    void cancelUsesTheShortClientAndReportsTheWorkerAnswer() {
        ReasonerWorkerClient client = new ReasonerWorkerClient("http://worker:8088", "token");
        RestTemplate shortTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "shortRestTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(shortTemplate).build();
        server.expect(requestTo("http://worker:8088/api/reasoning/jobs/job-9"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("X-Ontocode-Internal-Token", "token"))
                .andRespond(withSuccess("{\"cancelled\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://worker:8088/api/reasoning/jobs/gone"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertEquals(Map.of("success", true, "cancelled", true), client.cancelJob("job-9"));
        assertEquals(Map.of("success", true, "cancelled", false), client.cancelJob("gone"));
        server.verify();
    }
}
