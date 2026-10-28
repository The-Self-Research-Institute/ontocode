package self.research.ontology.swrl.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OntologySizeLimitTest {

    private static final String QUERY = "Patient(?p) ^ hasAge(?p, ?a) -> sqwrl:select(?p, ?a)";

    private HttpServer server;
    private byte[] body;
    private boolean chunked;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        body = TriageHarness.triageText().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, chunked ? 0 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        RequestContextHolder.resetRequestAttributes();
    }

    private OntologyClientService client() {
        return new OntologyClientService(new RestTemplateBuilder(), "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void theDerivedLimitFollowsTheHeapOfTheJvm() {
        long desktop = OntologyBudget.maxAxioms(0, 512L * 1024 * 1024, 0.5, 2, 1000);
        long server = OntologyBudget.maxAxioms(0, 4096L * 1024 * 1024, 0.5, 2, 1000);

        assertEquals(134217, desktop);
        assertEquals(22369, OntologyBudget.maxAxioms(0, 512L * 1024 * 1024, OntologyBudget.DEFAULT_HEAP_FRACTION, 2,
                OntologyBudget.DEFAULT_BYTES_PER_AXIOM));
        assertTrue(server > desktop * 7);
        assertEquals(50000, OntologyBudget.maxAxioms(50000, 512L * 1024 * 1024, 0.5, 2, 1000));
        assertEquals(1000, OntologyBudget.maxAxioms(0, 1024, 0.5, 2, 1000));
    }

    @Test
    void anOntologyUnderTheLimitLoads() throws Exception {
        assertNotNull(client().fetchOntologyWithStamp("p1", null).ontology());
    }

    @Test
    void aDeclaredSizeOverTheLimitIsRefusedBeforeAnythingIsParsed() {
        OntologyClientService client = client();
        ReflectionTestUtils.setField(client, "maxOntologyAxioms", 10L);
        ReflectionTestUtils.setField(client, "ontologyTextBytesPerAxiom", 10L);

        OntologyTooLargeException refused = assertThrows(OntologyTooLargeException.class, () -> client.fetchOntologyWithStamp("p1", null));

        assertTrue(refused.getMessage().startsWith("This ontology is too large for the rule engine on this server"));
        assertEquals(10, refused.getLimitAxioms());
    }

    @Test
    void anUndeclaredSizeOverTheLimitStopsTheReadAndIsRefused() {
        chunked = true;
        OntologyClientService client = client();
        ReflectionTestUtils.setField(client, "maxOntologyAxioms", 10L);
        ReflectionTestUtils.setField(client, "ontologyTextBytesPerAxiom", 10L);

        assertThrows(OntologyTooLargeException.class, () -> client.fetchOntologyWithStamp("p1", null));
    }

    @Test
    void anOntologyWithMoreAxiomsThanTheLimitIsRefusedAfterItIsParsed() {
        OntologyClientService client = client();
        ReflectionTestUtils.setField(client, "maxOntologyAxioms", 5L);

        assertThrows(OntologyTooLargeException.class, () -> client.fetchOntologyWithStamp("p1", null));
    }

    @Test
    void theCallersTokenReachesTheEditorFromInsideTheGate() throws Exception {
        TriageHarnessWithClient harness = new TriageHarnessWithClient(client());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer user-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertTrue(harness.service.executeSqwrlQuery("p1", QUERY, "q", null).isSuccess());

        assertEquals("Bearer user-token", authorization.get());
        assertEquals(1, requests.get());
    }

    private static final class TriageHarnessWithClient {
        final SwrlEngineService service = new SwrlEngineService();

        TriageHarnessWithClient(OntologyClientService client) {
            ReflectionTestUtils.setField(service, "ontologyClient", client);
            ReflectionTestUtils.setField(service, "maxInferredAxioms", 1000);
            ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 60);
        }
    }
}
