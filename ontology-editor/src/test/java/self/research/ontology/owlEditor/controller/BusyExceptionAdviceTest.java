package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.common.admission.RefusalReason;
import self.research.ontology.owlEditor.service.BusyException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BusyExceptionAdviceTest {

    @RestController
    static class Thrower {
        @GetMapping("/busy")
        String busy() {
            throw new BusyException(RefusalReason.OVER_CAPACITY, "Not enough memory right now.", 1500);
        }

        @GetMapping("/large")
        String large() {
            throw new BusyException(RefusalReason.TOO_LARGE, "Too big.", 0);
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Thrower())
            .setControllerAdvice(new BusyExceptionAdvice(), new GlobalExceptionHandler()).build();

    @Test
    void busyBecomesA429WithTheAgreedBodyAndRetryAfterHeader() throws Exception {
        mvc.perform(get("/busy"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.busy").value(true))
                .andExpect(jsonPath("$.reason").value("OVER_CAPACITY"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(2))
                .andExpect(jsonPath("$.message").value("Not enough memory right now."));
    }

    @Test
    void aRefusalWithNoRetryHasNoRetryAfterHeader() throws Exception {
        mvc.perform(get("/large"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.reason").value("TOO_LARGE"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(0));
    }
}
