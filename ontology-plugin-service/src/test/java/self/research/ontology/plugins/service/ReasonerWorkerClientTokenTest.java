package self.research.ontology.plugins.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ReasonerWorkerClientTokenTest {

    @Test
    void submitAndStatusCallsCarryTheInternalToken() {
        ReasonerWorkerClient client = new ReasonerWorkerClient("http://worker:8088", "shared-secret");
        RestTemplate template = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        server.expect(requestTo("http://worker:8088/api/reasoning/jobs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Ontocode-Internal-Token", "shared-secret"))
                .andRespond(withSuccess("{\"jobId\":\"j1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://worker:8088/api/reasoning/jobs/j1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Ontocode-Internal-Token", "shared-secret"))
                .andRespond(withSuccess("{\"jobId\":\"j1\"}", MediaType.APPLICATION_JSON));

        client.submit("REASONER_HIERARCHY", "proj-1", "HERMIT");
        client.getJob("j1");

        server.verify();
    }
}
