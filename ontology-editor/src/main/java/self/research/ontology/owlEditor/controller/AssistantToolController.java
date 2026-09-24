package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.dto.ReadContextRequest;
import self.research.ontology.owlEditor.dto.RunSparqlRequest;
import self.research.ontology.owlEditor.service.AssistantContextToolService;
import self.research.ontology.owlEditor.service.AssistantContextToolService.ContextToolResult;
import self.research.ontology.owlEditor.service.AssistantSparqlToolService;
import self.research.ontology.owlEditor.service.AssistantSparqlToolService.SparqlToolResult;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/code-assistant/sessions/{sessionId}/tools")
@CrossOrigin(originPatterns = "*", allowedHeaders = "*", allowCredentials = "false",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class AssistantToolController {

    private static final String RATE_LIMITED = "RATE_LIMITED";

    private final AssistantSparqlToolService sparqlToolService;
    private final AssistantContextToolService contextToolService;

    public AssistantToolController(AssistantSparqlToolService sparqlToolService,
                                    AssistantContextToolService contextToolService) {
        this.sparqlToolService = sparqlToolService;
        this.contextToolService = contextToolService;
    }

    @PostMapping("/run_sparql")
    public ResponseEntity<?> runSparql(@PathVariable String sessionId, @RequestBody RunSparqlRequest request,
                                        HttpServletRequest httpRequest) {
        return JwtIdentityExtractor.extractEmail(httpRequest)
                .map(userEmail -> {
                    SparqlToolResult result = sparqlToolService.runSparql(sessionId, userEmail, request.query());
                    return respond(result.getErrorCode(), result.getRetryAfterSeconds(), toBody(result));
                })
                .orElseGet(AssistantToolController::unauthorized);
    }

    @PostMapping("/read_context")
    public ResponseEntity<?> readContext(@PathVariable String sessionId, @RequestBody ReadContextRequest request,
                                          HttpServletRequest httpRequest) {
        return JwtIdentityExtractor.extractEmail(httpRequest)
                .map(userEmail -> {
                    ContextToolResult result = contextToolService.readContext(
                            sessionId, userEmail, request.targets(), request.kind());
                    return respond(result.getErrorCode(), result.getRetryAfterSeconds(), toBody(result));
                })
                .orElseGet(AssistantToolController::unauthorized);
    }

    private static ResponseEntity<Map<String, Object>> respond(String errorCode, Integer retryAfterSeconds, Map<String, Object> body) {
        if (RATE_LIMITED.equals(errorCode)) {
            int retryAfter = retryAfterSeconds != null ? retryAfterSeconds : 1;
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                    .body(body);
        }
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> errorBody(String errorCode, String message, Integer retryAfterSeconds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("errorCode", errorCode);
        body.put("message", message);
        if (retryAfterSeconds != null) {
            body.put("retryAfterSeconds", retryAfterSeconds);
        }
        return body;
    }

    private static Map<String, Object> toBody(SparqlToolResult result) {
        if (!result.isOk()) {
            return errorBody(result.getErrorCode(), result.getMessage(), result.getRetryAfterSeconds());
        }
        return Map.of(
                "ok", true,
                "result", Map.of("rows", result.getRows(), "truncated", result.isTruncated(),
                        "rowCount", result.getRowCount()),
                "provenance", Map.of("revision", result.getRevision()));
    }

    private static Map<String, Object> toBody(ContextToolResult result) {
        if (!result.isOk()) {
            return errorBody(result.getErrorCode(), result.getMessage(), result.getRetryAfterSeconds());
        }
        return Map.of(
                "ok", true,
                "result", Map.of("items", result.getItems()),
                "provenance", Map.of("revision", result.getRevision(), "coverage", result.getCoverage()));
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("ok", false, "message", "Missing or invalid Authorization header"));
    }
}
