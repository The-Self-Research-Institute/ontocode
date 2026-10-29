package self.research.ontology.swrl.controller;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import self.research.ontology.swrl.model.InferredAxiom;
import self.research.ontology.swrl.service.AssertedAxiomLister;
import self.research.ontology.swrl.service.OntologyClientService;
import self.research.ontology.swrl.service.SwrlBusyException;
import self.research.ontology.swrl.service.SwrlEngineService;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssertedAxiomsEndpointHttpTest {

    private final SwrlEngineService engine = Mockito.mock(SwrlEngineService.class);
    private final SwrlController controller = new SwrlController();
    private final MockMvc mvc;

    AssertedAxiomsEndpointHttpTest() {
        ReflectionTestUtils.setField(controller, "swrlEngineService", engine);
        ReflectionTestUtils.setField(controller, "ontologyClient", Mockito.mock(OntologyClientService.class));
        ReflectionTestUtils.setField(controller, "internalToken", "secret");
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static AssertedAxiomLister.Page page() {
        InferredAxiom item = new InferredAxiom("ClassAssertion", "ClassAssertion(<a> <b>)", "ClassAssertion(<a> <b>)",
                "http://example.org/bakery#rye1", "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
                "http://example.org/bakery#Loaf", null, null, null);
        return new AssertedAxiomLister.Page(41, 1, 20, false, 60, 500_000, List.of(item));
    }

    @Test
    void theAnswerCarriesTotalPageSizeTruncationAndTheRenderedItems() throws Exception {
        Mockito.when(engine.listAssertedAxioms("p1", null, "rye", "ClassAssertion", 1, 20)).thenReturn(page());

        mvc.perform(get("/api/swrl/p1/axioms/asserted").param("search", "rye").param("type", "ClassAssertion")
                        .param("page", "1").param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(41))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.scanned").value(60))
                .andExpect(jsonPath("$.items[0].axiomType").value("ClassAssertion"))
                .andExpect(jsonPath("$.items[0].readable").value("ClassAssertion(<a> <b>)"))
                .andExpect(jsonPath("$.items[0].subjectIri").value("http://example.org/bakery#rye1"))
                .andExpect(jsonPath("$.items[0].objectIri").value("http://example.org/bakery#Loaf"));
    }

    @Test
    void theDefaultsAreThePublicOntologyFirstPageOfFifty() throws Exception {
        Mockito.when(engine.listAssertedAxioms("p1", null, null, null, 0, 50)).thenReturn(page());

        mvc.perform(get("/api/swrl/p1/axioms/asserted").param("userId", "u1")).andExpect(status().isOk());

        Mockito.verify(engine).listAssertedAxioms("p1", null, null, null, 0, 50);
    }

    @Test
    void aDraftRequestPassesTheUserAndWithoutAUserIsRefused() throws Exception {
        Mockito.when(engine.listAssertedAxioms("p1", "u1", null, null, 0, 50)).thenReturn(page());

        mvc.perform(get("/api/swrl/p1/axioms/asserted").param("draft", "true").param("userId", "u1")).andExpect(status().isOk());
        mvc.perform(get("/api/swrl/p1/axioms/asserted").param("draft", "true")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/swrl/p1/axioms/asserted").param("draft", "true").param("userId", " ")).andExpect(status().isBadRequest());

        Mockito.verify(engine).listAssertedAxioms("p1", "u1", null, null, 0, 50);
        Mockito.verifyNoMoreInteractions(engine);
    }

    @Test
    void aBadTypeOrNegativePageIsABadRequestWithTheReason() throws Exception {
        Mockito.when(engine.listAssertedAxioms("p1", null, null, "Bogus", 0, 50))
                .thenThrow(new IllegalArgumentException("Unknown axiom type 'Bogus'"));

        mvc.perform(get("/api/swrl/p1/axioms/asserted").param("type", "Bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown axiom type 'Bogus'"));
        mvc.perform(get("/api/swrl/p1/axioms/asserted").param("page", "-1")).andExpect(status().isBadRequest());
    }

    @Test
    void aBusyEngineAnswers429WithRetryAfter() throws Exception {
        Mockito.when(engine.listAssertedAxioms("p1", null, null, null, 0, 50))
                .thenThrow(new SwrlBusyException("queue_full", 5, "The rule engine is busy. Try again in a moment."));

        mvc.perform(get("/api/swrl/p1/axioms/asserted"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.busy").value(true));
    }

    @Test
    void anUnexpectedFailureAnswers500WithoutLeakingTheCause() throws Exception {
        Mockito.when(engine.listAssertedAxioms("p1", null, null, null, 0, 50)).thenThrow(new IllegalStateException("secret detail"));

        mvc.perform(get("/api/swrl/p1/axioms/asserted"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Could not read the axioms of the ontology"));
    }
}
