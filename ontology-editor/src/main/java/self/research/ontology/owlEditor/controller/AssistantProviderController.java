package self.research.ontology.owlEditor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.dto.AssistantUsageReport;
import self.research.ontology.owlEditor.service.AssistantProviderProxyService;
import self.research.ontology.owlEditor.service.AssistantProviderProxyService.ProviderCallResult;
import self.research.ontology.owlEditor.service.AssistantProviderProxyService.ProviderConfigView;
import self.research.ontology.owlEditor.service.AssistantProviderRateLimiter;
import self.research.ontology.owlEditor.service.AssistantSessionService;
import self.research.ontology.owlEditor.service.AssistantUsageMetricsService;
import self.research.ontology.owlEditor.service.AssistantUsageMetricsService.UsageOutcome;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/code-assistant")
@CrossOrigin(originPatterns = "*", allowedHeaders = "*", allowCredentials = "false",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class AssistantProviderController {

    private static final int ENVELOPE_OVERHEAD_BYTES = 1024;

    private final AssistantProviderProxyService proxyService;
    private final AssistantSessionService sessionService;
    private final AssistantUsageMetricsService usageMetricsService;
    private final ObjectMapper objectMapper;

    public AssistantProviderController(AssistantProviderProxyService proxyService,
                                       AssistantSessionService sessionService,
                                       AssistantUsageMetricsService usageMetricsService,
                                       ObjectMapper objectMapper) {
        this.proxyService = proxyService;
        this.sessionService = sessionService;
        this.usageMetricsService = usageMetricsService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/provider-config")
    public ResponseEntity<Map<String, Object>> providerConfig() {
        ProviderConfigView config = proxyService.config();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("managed", config.managed());
        if (config.managed()) {
            body.put("provider", config.provider());
            body.put("model", config.model());
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping("/sessions/{sessionId}/provider-call")
    public ResponseEntity<?> providerCall(@PathVariable String sessionId, HttpServletRequest httpRequest) {
        Optional<String> email = JwtIdentityExtractor.extractEmail(httpRequest);
        if (email.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid Authorization header");
        }
        String userEmail = email.get();
        if (!proxyService.isManaged()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
                    "Managed provider mode is not configured on this server");
        }
        if (sessionService.getActiveSession(sessionId, userEmail).isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session not found or no longer active");
        }
        AssistantProviderRateLimiter.Decision decision = proxyService.tryAcquireRate(userEmail);
        if (!decision.allowed()) {
            return rateLimited(decision.retryAfterSeconds(), "Too many provider calls, try again shortly");
        }

        int limit = proxyService.getMaxRequestBytes() + ENVELOPE_OVERHEAD_BYTES;
        if (httpRequest.getContentLengthLong() > limit) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "VALIDATION_FAILED", "request is larger than the allowed size");
        }
        JsonNode envelope;
        try {
            byte[] raw = readBounded(httpRequest.getInputStream(), limit);
            if (raw == null) {
                return error(HttpStatus.PAYLOAD_TOO_LARGE, "VALIDATION_FAILED",
                        "request is larger than the allowed size");
            }
            envelope = objectMapper.readTree(raw);
        } catch (IOException e) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "body must be a JSON object with a request field");
        }
        JsonNode providerRequest = envelope == null ? null : envelope.get("request");
        if (providerRequest == null || !providerRequest.isObject()) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "body must be a JSON object with a request field");
        }

        ProviderCallResult result = proxyService.forward(providerRequest);
        if (result.isError()) {
            if (result.status() == 429 && result.retryAfterSeconds() != null) {
                return rateLimited(result.retryAfterSeconds(), result.message());
            }
            return error(HttpStatus.valueOf(result.status()), result.errorCode(), result.message());
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON);
        if (result.retryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(result.retryAfterSeconds()));
        }
        return builder.body(result.body());
    }

    @PostMapping("/sessions/{sessionId}/usage")
    public ResponseEntity<?> reportUsage(@PathVariable String sessionId,
                                         @RequestBody(required = false) AssistantUsageReport report,
                                         HttpServletRequest httpRequest) {
        Optional<String> email = JwtIdentityExtractor.extractEmail(httpRequest);
        if (email.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid Authorization header");
        }
        UsageOutcome outcome = usageMetricsService.record(sessionId, email.get(), report);
        if (outcome.isOk()) {
            return ResponseEntity.noContent().build();
        }
        if (outcome.status() == 429 && outcome.retryAfterSeconds() != null) {
            return rateLimited(outcome.retryAfterSeconds(), outcome.message());
        }
        return error(HttpStatus.valueOf(outcome.status()), outcome.errorCode(), outcome.message());
    }

    static byte[] readBounded(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String errorCode, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("errorCode", errorCode);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    private static ResponseEntity<Map<String, Object>> rateLimited(long retryAfterSeconds, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("errorCode", "RATE_LIMITED");
        body.put("message", message);
        body.put("retryAfterSeconds", retryAfterSeconds);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(body);
    }
}
