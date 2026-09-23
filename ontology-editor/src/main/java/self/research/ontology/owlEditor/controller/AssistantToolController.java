package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
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

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/code-assistant/sessions/{sessionId}/tools")
@CrossOrigin(originPatterns = "*", allowedHeaders = "*", allowCredentials = "false",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class AssistantToolController {

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
                    return ResponseEntity.ok(toBody(result));
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
                    return ResponseEntity.ok(toBody(result));
                })
                .orElseGet(AssistantToolController::unauthorized);
    }

    private static Map<String, Object> toBody(SparqlToolResult result) {
        if (!result.isOk()) {
            return Map.of("ok", false, "errorCode", result.getErrorCode(), "message", result.getMessage());
        }
        return Map.of(
                "ok", true,
                "result", Map.of("rows", result.getRows(), "truncated", result.isTruncated(),
                        "rowCount", result.getRowCount()),
                "provenance", Map.of("revision", result.getRevision()));
    }

    private static Map<String, Object> toBody(ContextToolResult result) {
        if (!result.isOk()) {
            return Map.of("ok", false, "errorCode", result.getErrorCode(), "message", result.getMessage());
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
