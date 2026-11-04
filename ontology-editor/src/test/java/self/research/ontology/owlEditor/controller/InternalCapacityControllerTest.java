package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import self.research.ontology.owlEditor.config.InternalApiAuthInterceptor;
import self.research.ontology.owlEditor.service.FakeMemorySignals;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalCapacityControllerTest {

    private MockMvc mvc() {
        InternalApiAuthInterceptor interceptor = new InternalApiAuthInterceptor();
        ReflectionTestUtils.setField(interceptor, "expectedToken", "secret-token");
        return MockMvcBuilders.standaloneSetup(new InternalCapacityController(new FakeMemorySignals().ledger()))
                .addMappedInterceptors(new String[] {"/internal/**"}, interceptor).build();
    }

    @Test
    void withoutTheInternalTokenTheReportIsRefused() throws Exception {
        mvc().perform(get("/internal/capacity")).andExpect(status().isUnauthorized());
        mvc().perform(get("/internal/capacity").header("X-Ontocode-Internal-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void withTheTokenTheReportIsJson() throws Exception {
        mvc().perform(get("/internal/capacity").header("X-Ontocode-Internal-Token", "secret-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacityBytes").isNumber())
                .andExpect(jsonPath("$.kinds").isArray())
                .andExpect(jsonPath("$.configSources").exists());
    }

    @Test
    void textFormatRendersTheTable() throws Exception {
        mvc().perform(get("/internal/capacity").param("format", "text")
                        .header("X-Ontocode-Internal-Token", "secret-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN));
    }
}
