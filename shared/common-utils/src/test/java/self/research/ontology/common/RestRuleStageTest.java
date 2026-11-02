package self.research.ontology.common;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestRuleStageTest {

    private HttpServer server;
    private final AtomicReference<String> tokenSeen = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    private String start(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/swrl/infer/ontology-rules", exchange -> {
            calls.incrementAndGet();
            tokenSeen.set(exchange.getRequestHeaders().getFirst("X-Ontocode-Internal-Token"));
            byte[] body = "Prefix(owl:=<http://www.w3.org/2002/07/owl#>)\nOntology(\n)\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static OWLOntology empty() throws Exception {
        return OWLManager.createOWLOntologyManager().createOntology();
    }

    @Test
    void everyRequestCarriesTheInternalToken() throws Exception {
        RestRuleStage stage = new RestRuleStage(start(200), Duration.ofSeconds(5), "secret-1");

        assertTrue(stage.infer(empty(), List.of()).isPresent());

        assertEquals("secret-1", tokenSeen.get());
    }

    @Test
    void theDefaultTokenIsUsedWhenNoneIsConfigured() throws Exception {
        RestRuleStage stage = new RestRuleStage(start(200), Duration.ofSeconds(5), null);

        stage.infer(empty(), List.of());

        assertEquals("ontocode-internal", tokenSeen.get());
    }

    @Test
    void aRefusedRequestLeavesTheRulesOutAndStopsHammeringTheService() throws Exception {
        RestRuleStage stage = new RestRuleStage(start(401), Duration.ofSeconds(5), "wrong");

        assertTrue(stage.infer(empty(), List.of()).isEmpty());
        assertTrue(stage.infer(empty(), List.of()).isEmpty());

        assertEquals(1, calls.get());
    }

    private String startWithBody(int status, String json) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/swrl/infer/ontology-rules", exchange -> {
            calls.incrementAndGet();
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void aBusyAnswerIsNotAFailureAndDoesNotSwitchTheEngineOffForAMinute() throws Exception {
        RestRuleStage stage = new RestRuleStage(startWithBody(429,
                "{\"busy\":true,\"reason\":\"queue_full\",\"retryAfterSeconds\":5,\"message\":\"busy\"}"), Duration.ofSeconds(5), "t");

        assertTrue(stage.infer(empty(), List.of()).isEmpty());
        assertEquals("The rule engine is busy. 0 rules were not run. Try again in a moment.", RestRuleStage.takeBusyLimitation());
        assertTrue(stage.infer(empty(), List.of()).isEmpty());

        assertEquals(2, calls.get());
    }

    @Test
    void aBusy503IsNotRetriedForTheStartupWaitAndIsNotAFailure() throws Exception {
        RestRuleStage stage = new RestRuleStage(startWithBody(503, "{ \"busy\" : true, \"reason\": \"owner_limit\" }"), Duration.ofSeconds(5), "t");

        assertTrue(stage.infer(empty(), List.of()).isEmpty());
        assertTrue(stage.infer(empty(), List.of()).isEmpty());

        assertEquals(2, calls.get());
        assertTrue(RestRuleStage.takeBusyLimitation().startsWith("The rule engine is busy."));
    }

    @Test
    void theBusyTextCountsRulesInTheSingular() {
        assertEquals("The rule engine is busy. 1 rule was not run. Try again in a moment.", RestRuleStage.busyLimitation(1));
        assertEquals("The rule engine is busy. 3 rules were not run. Try again in a moment.", RestRuleStage.busyLimitation(3));
    }

    @Test
    void aRealFailureStillSwitchesTheEngineOffAndLeavesNoBusyNote() throws Exception {
        RestRuleStage stage = new RestRuleStage(startWithBody(500, "{\"error\":\"boom\"}"), Duration.ofSeconds(5), "t");

        assertTrue(stage.infer(empty(), List.of()).isEmpty());
        assertTrue(stage.infer(empty(), List.of()).isEmpty());

        assertEquals(1, calls.get());
        assertEquals(null, RestRuleStage.takeBusyLimitation());
    }

    @Test
    void a429WithoutTheBusyMarkerIsStillAFailure() throws Exception {
        RestRuleStage stage = new RestRuleStage(startWithBody(429, "{\"error\":\"rate limited\"}"), Duration.ofSeconds(5), "t");

        stage.infer(empty(), List.of());
        stage.infer(empty(), List.of());

        assertEquals(1, calls.get());
    }

    @Test
    void anAnsweredRequestIsKeptSoTheSameOntologyDoesNotAskAgain() throws Exception {
        RestRuleStage stage = new RestRuleStage(start(200), Duration.ofSeconds(5), "secret-1");
        OWLOntology ontology = empty();

        stage.infer(ontology, List.of());
        stage.infer(ontology, List.of());

        assertEquals(1, calls.get());
    }
}
