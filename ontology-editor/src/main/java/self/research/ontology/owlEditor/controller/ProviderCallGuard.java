package self.research.ontology.owlEditor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import self.research.ontology.owlEditor.service.AssistantProviderProxyService;
import self.research.ontology.owlEditor.service.AssistantProviderRateLimiter;
import self.research.ontology.owlEditor.service.AssistantSessionService;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class ProviderCallGuard {

    private static final int ENVELOPE_OVERHEAD_BYTES = 1024;

    record Admitted(JsonNode providerRequest, ResponseEntity<Map<String, Object>> rejection) {
        boolean rejected() {
            return rejection != null;
        }
    }

    private final AssistantProviderProxyService proxyService;
    private final AssistantSessionService sessionService;
    private final ObjectMapper objectMapper;

    ProviderCallGuard(AssistantProviderProxyService proxyService, AssistantSessionService sessionService,
                      ObjectMapper objectMapper) {
        this.proxyService = proxyService;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    Admitted admit(String sessionId, HttpServletRequest httpRequest) {
        Optional<String> email = JwtIdentityExtractor.extractEmail(httpRequest);
        if (email.isEmpty()) {
            return reject(error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid Authorization header"));
        }
        if (!proxyService.isManaged()) {
            return reject(error(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
                    "Managed provider mode is not configured on this server"));
        }
        if (sessionService.getActiveSession(sessionId, email.get()).isEmpty()) {
            return reject(error(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session not found or no longer active"));
        }
        AssistantProviderRateLimiter.Decision decision = proxyService.tryAcquireRate(email.get());
        if (!decision.allowed()) {
            return reject(rateLimited(decision.retryAfterSeconds(), "Too many provider calls, try again shortly"));
        }
        return readEnvelope(httpRequest);
    }

    private Admitted readEnvelope(HttpServletRequest httpRequest) {
        int limit = proxyService.getMaxRequestBytes() + ENVELOPE_OVERHEAD_BYTES;
        ResponseEntity<Map<String, Object>> tooLarge = error(HttpStatus.PAYLOAD_TOO_LARGE, "VALIDATION_FAILED",
                "request is larger than the allowed size");
        if (httpRequest.getContentLengthLong() > limit) {
            return reject(tooLarge);
        }
        ResponseEntity<Map<String, Object>> malformed = error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "body must be a JSON object with a request field");
        try {
            byte[] raw = AssistantProviderController.readBounded(httpRequest.getInputStream(), limit);
            if (raw == null) {
                return reject(tooLarge);
            }
            JsonNode envelope = objectMapper.readTree(raw);
            JsonNode providerRequest = envelope == null ? null : envelope.get("request");
            if (providerRequest == null || !providerRequest.isObject()) {
                return reject(malformed);
            }
            return new Admitted(providerRequest, null);
        } catch (IOException e) {
            return reject(malformed);
        }
    }

    private static Admitted reject(ResponseEntity<Map<String, Object>> rejection) {
        return new Admitted(null, rejection);
    }

    static ResponseEntity<Map<String, Object>> error(HttpStatus status, String errorCode, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("errorCode", errorCode);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    static ResponseEntity<Map<String, Object>> rateLimited(long retryAfterSeconds, String message) {
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
