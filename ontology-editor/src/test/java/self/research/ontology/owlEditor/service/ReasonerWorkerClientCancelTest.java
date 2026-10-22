package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ReasonerWorkerClientCancelTest {

    private ReasonerWorkerClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        client = new ReasonerWorkerClient("http://worker:8088/", "secret");
        RestTemplate shortTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "shortRestTemplate");
        server = MockRestServiceServer.bindTo(shortTemplate).build();
    }

    @Test
    void cancelSendsDeleteWithTheInternalTokenAndReportsTheWorkerAnswer() {
        server.expect(requestTo("http://worker:8088/api/reasoning/jobs/job-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("X-Ontocode-Internal-Token", "secret"))
                .andRespond(withSuccess("{\"jobId\":\"job-1\",\"status\":\"FAILED\",\"cancelled\":true}",
                        MediaType.APPLICATION_JSON));

        Map<String, Object> result = client.cancelJob("job-1");

        assertEquals(true, result.get("success"));
        assertEquals(true, result.get("cancelled"));
        server.verify();
    }

    @Test
    void sharedJobThatKeepsRunningIsReportedAsNotCancelled() {
        server.expect(requestTo("http://worker:8088/api/reasoning/jobs/job-1"))
                .andRespond(withSuccess("{\"jobId\":\"job-1\",\"status\":\"PROCESSING\",\"cancelled\":false}",
                        MediaType.APPLICATION_JSON));

        Map<String, Object> result = client.cancelJob("job-1");

        assertEquals(true, result.get("success"));
        assertEquals(false, result.get("cancelled"));
    }

    @Test
    void unknownJobIsNotAnError() {
        server.expect(requestTo("http://worker:8088/api/reasoning/jobs/gone"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        Map<String, Object> result = client.cancelJob("gone");

        assertEquals(true, result.get("success"));
        assertEquals(false, result.get("cancelled"));
    }

    @Test
    void workerFailureIsReportedWithoutThrowing() {
        server.expect(requestTo("http://worker:8088/api/reasoning/jobs/job-1"))
                .andRespond(withServerError());

        Map<String, Object> result = client.cancelJob("job-1");

        assertEquals(false, result.get("success"));
        assertEquals(false, result.get("cancelled"));
        assertTrue(result.containsKey("error"));
    }
}
