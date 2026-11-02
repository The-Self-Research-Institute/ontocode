package self.research.ontology.common.rules;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.OWLOntology;
import self.research.ontology.common.RestRuleStage;
import self.research.ontology.common.rules.RuleExecutionResult.Status;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleExecutionBusyEngineTest {

    private HttpServer server;

    private String start(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/swrl/infer/ontology-rules", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
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

    @Test
    void aBusyRuleEngineIsReportedAsBusyNotAsUnreachable() throws Exception {
        String base = start(429, "{\"busy\":true,\"reason\":\"queue_full\",\"retryAfterSeconds\":5,\"message\":\"busy\"}");
        OWLOntology ontology = RuleExecutionTest.fixture("04-maths-builtin.ofn");

        RuleExecutionResult result = RuleExecutionTest.run(ontology, new RestRuleStage(base, Duration.ofSeconds(5), "t"));

        assertEquals(Status.PARTIAL_ENGINE_UNAVAILABLE, result.status());
        assertTrue(result.limitations().stream().anyMatch(text -> text.startsWith("The rule engine is busy.")));
        assertTrue(result.message().startsWith("The rule engine is busy"));
        assertFalse(result.skippedRules().isEmpty());
        assertTrue(result.skippedRules().stream().allMatch(rule -> "The rule engine is busy.".equals(rule.reason())));
    }

    @Test
    void anUnreachableEngineStillSaysSo() throws Exception {
        String base = start(500, "{\"error\":\"boom\"}");
        OWLOntology ontology = RuleExecutionTest.fixture("04-maths-builtin.ofn");

        RuleExecutionResult result = RuleExecutionTest.run(ontology, new RestRuleStage(base, Duration.ofSeconds(5), "t"));

        assertEquals(Status.PARTIAL_ENGINE_UNAVAILABLE, result.status());
        assertTrue(result.limitations().stream().noneMatch(text -> text.contains("busy")));
        assertTrue(result.skippedRules().stream().allMatch(rule -> "The rule engine could not be reached.".equals(rule.reason())));
    }
}
