package self.research.ontology.owlEditor.service;

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
    void everyWorkerCallCarriesTheInternalToken() {
        ReasonerWorkerClient client = new ReasonerWorkerClient("http://worker:8088", "shared-secret");
        RestTemplate longTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        RestTemplate shortTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "shortRestTemplate");
        MockRestServiceServer longServer = MockRestServiceServer.bindTo(longTemplate).build();
        MockRestServiceServer shortServer = MockRestServiceServer.bindTo(shortTemplate).build();
        longServer.expect(requestTo("http://worker:8088/api/reasoning/jobs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Ontocode-Internal-Token", "shared-secret"))
                .andRespond(withSuccess("{\"jobId\":\"j1\"}", MediaType.APPLICATION_JSON));
        longServer.expect(requestTo("http://worker:8088/api/reasoning/jobs/j1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Ontocode-Internal-Token", "shared-secret"))
                .andRespond(withSuccess("{\"jobId\":\"j1\"}", MediaType.APPLICATION_JSON));
        shortServer.expect(requestTo("http://worker:8088/api/reasoning/jobs/j1"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("X-Ontocode-Internal-Token", "shared-secret"))
                .andRespond(withSuccess("{\"cancelled\":true}", MediaType.APPLICATION_JSON));

        client.submitJob("REASONER_HIERARCHY", "proj-1", null, null, "HERMIT", null);
        client.getJob("j1");
        client.cancelJob("j1");

        longServer.verify();
        shortServer.verify();
    }
}
