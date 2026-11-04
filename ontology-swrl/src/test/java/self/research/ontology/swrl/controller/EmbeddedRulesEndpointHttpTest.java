package self.research.ontology.swrl.controller;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import self.research.ontology.swrl.service.OntologyClientService;
import self.research.ontology.swrl.service.SwrlEngineService;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmbeddedRulesEndpointHttpTest {

    private final SwrlEngineService engine = Mockito.mock(SwrlEngineService.class);
    private final SwrlController controller = new SwrlController();
    private final MockMvc mvc;

    EmbeddedRulesEndpointHttpTest() {
        ReflectionTestUtils.setField(controller, "swrlEngineService", engine);
        ReflectionTestUtils.setField(controller, "ontologyClient", Mockito.mock(OntologyClientService.class));
        ReflectionTestUtils.setField(controller, "internalToken", "secret");
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void theListCarriesIdNameTextCommentCategoryEnabledSourceAndReadOnly() throws Exception {
        Mockito.when(engine.listEmbeddedRules("p1", "u1")).thenReturn(List.of(
                new SwrlEngineService.EmbeddedRule("id-1", "Age under 18", "Age under 18", "A(?x) -> B(?x)", "note", "Demo", false, null, false),
                new SwrlEngineService.EmbeddedRule("id-2", "Imported", "Imported", "C(?x) -> D(?x)", null, null, true,
                        "http://example.org/base", true)));

        mvc.perform(get("/api/swrl/p1/rules/embedded").param("draft", "true").param("userId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("id-1"))
                .andExpect(jsonPath("$[0].ruleName").value("Age under 18"))
                .andExpect(jsonPath("$[0].ruleText").value("A(?x) -> B(?x)"))
                .andExpect(jsonPath("$[0].comment").value("note"))
                .andExpect(jsonPath("$[0].category").value("Demo"))
                .andExpect(jsonPath("$[0].enabled").value(false))
                .andExpect(jsonPath("$[0].source").doesNotExist())
                .andExpect(jsonPath("$[0].readOnly").value(false))
                .andExpect(jsonPath("$[1].source").value("http://example.org/base"))
                .andExpect(jsonPath("$[1].readOnly").value(true));
    }

    @Test
    void withoutTheDraftFlagThePublicOntologyIsRead() throws Exception {
        Mockito.when(engine.listEmbeddedRules("p1", null)).thenReturn(List.of());

        mvc.perform(get("/api/swrl/p1/rules/embedded").param("userId", "u1")).andExpect(status().isOk());

        Mockito.verify(engine).listEmbeddedRules("p1", null);
    }

    @Test
    void theParseEndpointAnswersValidAxiomErrorsAndSuggestions() throws Exception {
        Mockito.when(engine.parseRule("p1", "A(?x) -> B(?x)", null))
                .thenReturn(new SwrlEngineService.RuleParse(true, "Ontology()", List.of(), List.of()));
        Mockito.when(engine.parseRule("p1", "A(?x) -> Bb(?x)", null))
                .thenReturn(new SwrlEngineService.RuleParse(false, null, List.of("Unknown class"), List.of("Did you mean: B?")));

        mvc.perform(post("/api/swrl/p1/rules/parse").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleName\":\"r\",\"ruleText\":\"A(?x) -> B(?x)\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.axiom").value("Ontology()"));
        mvc.perform(post("/api/swrl/p1/rules/parse").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleName\":\"r\",\"ruleText\":\"A(?x) -> Bb(?x)\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0]").value("Unknown class"))
                .andExpect(jsonPath("$.suggestions[0]").value("Did you mean: B?"));
    }

    @Test
    void theOldWriteEndpointsAnswer410() throws Exception {
        mvc.perform(post("/api/swrl/p1/rules").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("/api/ontology/p1/rules")));
    }
}
