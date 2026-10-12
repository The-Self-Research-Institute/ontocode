package self.research.ontology.owlEditor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.service.AssistantProviderProxyService;
import self.research.ontology.owlEditor.service.AssistantProviderRateLimiter;
import self.research.ontology.owlEditor.service.AssistantSessionService;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssistantProviderStreamControllerTest {

    private AssistantProviderProxyService proxyService;
    private AssistantSessionService sessionService;
    private MockMvc mvc;

    private static String bearer(String email) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + enc.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + enc.encodeToString(("{\"email\":\"" + email + "\"}").getBytes(StandardCharsets.UTF_8)) + ".";
    }

    @BeforeEach
    void setUp() {
        proxyService = mock(AssistantProviderProxyService.class);
        sessionService = mock(AssistantSessionService.class);
        when(proxyService.isManaged()).thenReturn(true);
        when(proxyService.getMaxRequestBytes()).thenReturn(10_000);
        when(proxyService.tryAcquireRate(anyString())).thenReturn(new AssistantProviderRateLimiter.Decision(true, 0));
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(AssistantSessionDocument.builder().id("s1").build()));
        mvc = MockMvcBuilders.standaloneSetup(
                new AssistantProviderStreamController(proxyService, sessionService, new ObjectMapper())).build();
    }

    @Test
    void relaysProviderEventsAsAnEventStream() throws Exception {
        doAnswer(inv -> {
            AssistantProviderProxyService.StreamSink sink = inv.getArgument(1);
            sink.event("{\"type\":\"content_block_delta\"}");
            sink.complete("{\"type\":\"message\"}");
            return null;
        }).when(proxyService).stream(any(), any());

        MvcResult started = mvc.perform(post("/api/v1/code-assistant/sessions/s1/provider-call/stream")
                        .header("Authorization", bearer("u@x.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request\":{\"messages\":[]}}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string("data: {\"type\":\"content_block_delta\"}\n\nevent: complete\ndata: {\"type\":\"message\"}\n\n"));
    }

    @Test
    void rejectsAMissingTokenWithAJsonError() throws Exception {
        MvcResult started = mvc.perform(post("/api/v1/code-assistant/sessions/s1/provider-call/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request\":{}}"))
                .andReturn();

        mvc.perform(asyncDispatch(started))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }
}
