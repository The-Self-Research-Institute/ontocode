package self.research.ontology.swrl.controller;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import self.research.ontology.swrl.service.OntologyClientService;
import self.research.ontology.swrl.service.SwrlEngineService;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RulesOwlEndpointHttpTest {

    private final SwrlEngineService engine = Mockito.mock(SwrlEngineService.class);
    private final SwrlController controller = new SwrlController();
    private final MockMvc mvc;

    RulesOwlEndpointHttpTest() {
        ReflectionTestUtils.setField(controller, "swrlEngineService", engine);
        ReflectionTestUtils.setField(controller, "ontologyClient", Mockito.mock(OntologyClientService.class));
        ReflectionTestUtils.setField(controller, "internalToken", "secret");
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void aMissingTokenIsAnswered401WithJsonNotAServerError() throws Exception {
        mvc.perform(get("/api/swrl/p1/rules/owl"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void anExportFailureIsAnswered500WithJson() throws Exception {
        Mockito.when(engine.exportEnabledRulesAsOwl("p1", null)).thenThrow(new IllegalStateException("boom"));

        mvc.perform(get("/api/swrl/p1/rules/owl").header("X-Ontocode-Internal-Token", "secret"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @Test
    void aSuccessfulExportIsPlainTextWithTheFingerprintHeader() throws Exception {
        Mockito.when(engine.exportEnabledRulesAsOwl("p1", null))
                .thenReturn(new SwrlEngineService.RulesExport("Ontology()", List.of(), "abc123"));

        mvc.perform(get("/api/swrl/p1/rules/owl").header("X-Ontocode-Internal-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(header().string("X-Ontocode-Rules-Fingerprint", "abc123"))
                .andExpect(content().string("Ontology()"));
    }
}
