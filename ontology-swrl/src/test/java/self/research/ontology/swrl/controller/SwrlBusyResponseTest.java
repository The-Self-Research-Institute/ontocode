package self.research.ontology.swrl.controller;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.swrl.config.OwnerContextFilter;
import self.research.ontology.swrl.service.ExecutionGate;
import self.research.ontology.swrl.service.SwrlBusyException;
import self.research.ontology.swrl.service.SwrlEngineService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwrlBusyResponseTest {

    @Test
    void aBusyRefusalBecomes429WithTheBusyBody() {
        SwrlEngineService service = Mockito.mock(SwrlEngineService.class);
        Mockito.when(service.executeRules("p1", null))
                .thenThrow(new SwrlBusyException("queue_full", 5, "The rule engine is busy. Try again in a moment."));
        SwrlController controller = new SwrlController();
        ReflectionTestUtils.setField(controller, "swrlEngineService", service);

        ResponseEntity<?> response = controller.executeRules("p1", false, null);

        assertEquals(429, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(true, body.get("busy"));
        assertEquals("queue_full", body.get("reason"));
        assertEquals(5, body.get("retryAfterSeconds"));
        assertTrue(body.get("message").toString().contains("busy"));
        assertEquals("5", response.getHeaders().getFirst("Retry-After"));
    }

    @Test
    void theStatsEndpointNeedsTheInternalTokenAndReportsCounts() {
        SwrlEngineService service = Mockito.mock(SwrlEngineService.class);
        Mockito.when(service.executionStats()).thenReturn(new ExecutionGate.Stats(2, 10, 1, 3, 4, 5, 6, 7, 800));
        SwrlController controller = new SwrlController();
        ReflectionTestUtils.setField(controller, "swrlEngineService", service);
        ReflectionTestUtils.setField(controller, "internalToken", "t");

        assertEquals(401, controller.executionStats("wrong").getStatusCode().value());
        ResponseEntity<?> ok = controller.executionStats("t");

        assertEquals(200, ok.getStatusCode().value());
        assertEquals(1, ((ExecutionGate.Stats) ok.getBody()).active());
    }

    @Test
    void theOwnerIsTheForwardedUserElseTheProject() {
        assertEquals("user:u1", OwnerContextFilter.ownerOf("u1", "/api/swrl/p1/execute"));
        assertEquals("project:p1", OwnerContextFilter.ownerOf(null, "/api/swrl/p1/execute"));
        assertNull(OwnerContextFilter.ownerOf(null, "/api/swrl/infer/ontology-rules"));
    }
}
