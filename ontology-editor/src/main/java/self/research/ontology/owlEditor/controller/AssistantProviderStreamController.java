package self.research.ontology.owlEditor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import self.research.ontology.owlEditor.service.AssistantProviderProxyService;
import self.research.ontology.owlEditor.service.AssistantSessionService;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/code-assistant")
@CrossOrigin(originPatterns = "*", allowedHeaders = "*", allowCredentials = "false",
        methods = {RequestMethod.POST, RequestMethod.OPTIONS})
public class AssistantProviderStreamController {

    private final AssistantProviderProxyService proxyService;
    private final ObjectMapper objectMapper;
    private final ProviderCallGuard guard;

    public AssistantProviderStreamController(AssistantProviderProxyService proxyService,
                                             AssistantSessionService sessionService,
                                             ObjectMapper objectMapper) {
        this.proxyService = proxyService;
        this.objectMapper = objectMapper;
        this.guard = new ProviderCallGuard(proxyService, sessionService, objectMapper);
    }

    @PostMapping("/sessions/{sessionId}/provider-call/stream")
    public ResponseEntity<StreamingResponseBody> providerCallStream(@PathVariable String sessionId,
                                                                    HttpServletRequest httpRequest) {
        ProviderCallGuard.Admitted admitted = guard.admit(sessionId, httpRequest);
        if (admitted.rejected()) {
            return asJson(admitted.rejection());
        }
        JsonNode providerRequest = admitted.providerRequest();
        StreamingResponseBody body = out -> proxyService.stream(providerRequest, sinkFor(out));
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    private AssistantProviderProxyService.StreamSink sinkFor(OutputStream out) {
        return new AssistantProviderProxyService.StreamSink() {
            @Override
            public void event(String data) throws IOException {
                write(out, "data: " + data + "\n\n");
            }

            @Override
            public void complete(String json) throws IOException {
                write(out, "event: complete\ndata: " + json + "\n\n");
            }

            @Override
            public void error(int status, String errorCode, String message, Long retryAfterSeconds) throws IOException {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("status", status);
                if (errorCode != null) {
                    payload.put("errorCode", errorCode);
                }
                payload.put("message", message);
                payload.put("error", Map.of("message", message));
                if (retryAfterSeconds != null) {
                    payload.put("retryAfterSeconds", retryAfterSeconds);
                }
                write(out, "event: proxy_error\ndata: " + objectMapper.writeValueAsString(payload) + "\n\n");
            }
        };
    }

    private ResponseEntity<StreamingResponseBody> asJson(ResponseEntity<Map<String, Object>> rejection) {
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(rejection.getBody());
        } catch (IOException e) {
            json = "{\"ok\":false}".getBytes(StandardCharsets.UTF_8);
        }
        byte[] body = json;
        return ResponseEntity.status(rejection.getStatusCode())
                .headers(rejection.getHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .body(out -> out.write(body));
    }

    private static void write(OutputStream out, String frame) throws IOException {
        out.write(frame.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
