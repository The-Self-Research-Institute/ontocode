package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EditorClientTest {

    private static final String EVENTS_URL = "http://editor/internal/reasoning/job-events";

    @Test
    void clientErrorFromTheEditorIsNotRetried() {
        EditorClient client = new EditorClient("http://editor/", "token", 2000, 5000);
        MockRestServiceServer server = MockRestServiceServer.bindTo(quickTemplate(client)).build();
        server.expect(ExpectedCount.once(), requestTo(EVENTS_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        client.publishJobEvent(ReasoningJobEventSenderTest.snapshot("job-1", "COMPLETED"));

        server.verify();
    }

    @Test
    void serverErrorIsRetriedUntilTheEditorAccepts() {
        EditorClient client = new EditorClient("http://editor", "token", 2000, 5000);
        MockRestServiceServer server = MockRestServiceServer.bindTo(quickTemplate(client)).build();
        server.expect(ExpectedCount.once(), requestTo(EVENTS_URL)).andRespond(withServerError());
        server.expect(ExpectedCount.once(), requestTo(EVENTS_URL)).andRespond(withSuccess());

        client.publishJobEvent(ReasoningJobEventSenderTest.snapshot("job-1", "COMPLETED"));

        server.verify();
    }

    @Test
    void sizeAndRevisionChecksUseTheShortTimeoutClient() {
        EditorClient client = new EditorClient("http://editor", "token", 2000, 5000);
        MockRestServiceServer server = MockRestServiceServer.bindTo(quickTemplate(client)).build();
        server.expect(requestTo("http://editor/internal/reasoning/P1/triple-count"))
                .andRespond(withSuccess("{\"tripleCount\":42}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://editor/internal/reasoning/P1/revision"))
                .andRespond(withSuccess("{\"revision\":7}", MediaType.APPLICATION_JSON));

        assertEquals(42L, client.getTripleCount("P1"));
        assertEquals(7L, client.getRevision("P1"));

        server.verify();
    }

    private static RestTemplate quickTemplate(EditorClient client) {
        return (RestTemplate) ReflectionTestUtils.getField(client, "quickRestTemplate");
    }
}
