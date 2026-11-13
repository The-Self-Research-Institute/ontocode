package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwrlRuleClientTooLargeTest {

    private static SwrlRuleClient clientAnswering(int status, String body) {
        return new SwrlRuleClient((method, url, requestBody, token) -> new SwrlRuleClient.Answer(status, body),
                "http://swrl", "token");
    }

    @Test
    void a413KeepsTheReasonTheRuleServiceGave() {
        SwrlRuleClient client = clientAnswering(413,
                "{\"error\":true,\"message\":\"This ontology is too large for the rule engine on this server.\"}");

        SwrlRuleClient.TooLargeException thrown = assertThrows(SwrlRuleClient.TooLargeException.class,
                () -> client.listEmbedded("p1", false, null));

        assertEquals("This ontology is too large for the rule engine on this server.", thrown.getMessage());
    }

    @Test
    void a413WithoutAReadableBodyStillExplainsTheProblem() {
        SwrlRuleClient client = clientAnswering(413, "not json");

        SwrlRuleClient.TooLargeException thrown = assertThrows(SwrlRuleClient.TooLargeException.class,
                () -> client.listEmbedded("p1", false, null));

        assertTrue(thrown.getMessage().contains("too large"));
    }

    @Test
    void aTooLargeAnswerIsStillAnUnavailableException() {
        SwrlRuleClient client = clientAnswering(413, "{}");

        assertThrows(SwrlRuleClient.UnavailableException.class, () -> client.listEmbedded("p1", false, null));
    }

    @Test
    void aPlainServerErrorIsNotReportedAsTooLarge() {
        SwrlRuleClient client = clientAnswering(500, "{}");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> client.listEmbedded("p1", false, null));

        assertTrue(thrown instanceof SwrlRuleClient.UnavailableException);
        assertFalse(thrown instanceof SwrlRuleClient.TooLargeException);
    }
}
