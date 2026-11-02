package self.research.ontology.swrl.controller;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import self.research.ontology.swrl.model.SkippedRule;
import self.research.ontology.swrl.service.OntologyClientService;
import self.research.ontology.swrl.service.SwrlBusyException;
import self.research.ontology.swrl.service.SwrlEngineService;
import self.research.ontology.swrl.service.TransferSummary;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TransferSummaryEndpointHttpTest {

    private final SwrlEngineService engine = Mockito.mock(SwrlEngineService.class);
    private final SwrlController controller = new SwrlController();
    private final MockMvc mvc;

    TransferSummaryEndpointHttpTest() {
        ReflectionTestUtils.setField(controller, "swrlEngineService", engine);
        ReflectionTestUtils.setField(controller, "ontologyClient", Mockito.mock(OntologyClientService.class));
        ReflectionTestUtils.setField(controller, "internalToken", "secret");
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static TransferSummary summary() throws Exception {
        return new TransferSummary(8, 57, 45, 12, 5, 378,
                List.of(new SkippedRule("Count them", "This is a SQWRL query. It is not run as a rule.")), 17);
    }

    @Test
    void theAnswerCarriesEveryCountAndTheSkippedRules() throws Exception {
        Mockito.when(engine.transferSummary("p1", null)).thenReturn(summary());

        mvc.perform(get("/api/swrl/p1/transfer-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesExported").value(8))
                .andExpect(jsonPath("$.classDeclarations").value(57))
                .andExpect(jsonPath("$.individualDeclarations").value(45))
                .andExpect(jsonPath("$.objectPropertyDeclarations").value(12))
                .andExpect(jsonPath("$.dataPropertyDeclarations").value(5))
                .andExpect(jsonPath("$.totalAxioms").value(378))
                .andExpect(jsonPath("$.elapsedMs").value(17))
                .andExpect(jsonPath("$.skippedRules[0].ruleName").value("Count them"));
    }

    @Test
    void aDraftRequestPassesTheUserAndWithoutAUserIsRefused() throws Exception {
        Mockito.when(engine.transferSummary("p1", "u1")).thenReturn(summary());

        mvc.perform(get("/api/swrl/p1/transfer-summary").param("draft", "true").param("userId", "u1")).andExpect(status().isOk());
        mvc.perform(get("/api/swrl/p1/transfer-summary").param("draft", "true")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/swrl/p1/transfer-summary").param("draft", "true").param("userId", " ")).andExpect(status().isBadRequest());

        Mockito.verify(engine).transferSummary("p1", "u1");
        Mockito.verifyNoMoreInteractions(engine);
    }

    @Test
    void aBusyEngineAnswers429WithRetryAfter() throws Exception {
        Mockito.when(engine.transferSummary("p1", null))
                .thenThrow(new SwrlBusyException("queue_full", 5, "The rule engine is busy. Try again in a moment."));

        mvc.perform(get("/api/swrl/p1/transfer-summary"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.busy").value(true));
    }

    @Test
    void anUnexpectedFailureAnswers500WithoutLeakingTheCause() throws Exception {
        Mockito.when(engine.transferSummary("p1", null)).thenThrow(new IllegalStateException("secret detail"));

        mvc.perform(get("/api/swrl/p1/transfer-summary"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Could not check the ontology and rules"));
    }
}
