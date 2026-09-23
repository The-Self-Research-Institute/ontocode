package self.research.ontology.owlEditor.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AssistantSparqlToolService {

    private static final Pattern SELECT_ONLY =
            Pattern.compile("(?is)^\\s*(PREFIX\\s+\\S*\\s+<[^>]*>\\s*)*SELECT\\b");

    private final AssistantSessionService sessionService;
    private final SparqlDatasetService datasetService;

    @Value("${assistant.sparql.max-rows:200}")
    private int maxRows;

    @Value("${assistant.sparql.max-bytes:200000}")
    private long maxBytes;

    @Value("${assistant.sparql.timeout-seconds:15}")
    private int timeoutSeconds;

    public AssistantSparqlToolService(AssistantSessionService sessionService, SparqlDatasetService datasetService) {
        this.sessionService = sessionService;
        this.datasetService = datasetService;
    }

    public SparqlToolResult runSparql(String sessionId, String userEmail, String query) {
        Optional<AssistantSessionDocument> sessionOpt = sessionService.getActiveSession(sessionId, userEmail);
        if (sessionOpt.isEmpty()) {
            return SparqlToolResult.builder().ok(false).errorCode("SESSION_NOT_FOUND")
                    .message("Session not found, expired, or not yours").build();
        }
        AssistantSessionDocument session = sessionOpt.get();

        if (query == null || !SELECT_ONLY.matcher(query).find()) {
            return SparqlToolResult.builder().ok(false).errorCode("NOT_SELECT_ONLY")
                    .message("Only SELECT queries are allowed").build();
        }

        if (!sessionService.tryConsumeRetrievalAttempt(sessionId)) {
            return SparqlToolResult.builder().ok(false).errorCode("BUDGET_EXHAUSTED")
                    .message("Retrieval budget exhausted for this session")
                    .retrievalCallsRemaining(0).build();
        }

        try {
            SparqlDatasetService.CappedSparqlResult capped = datasetService.execSelectCapped(
                    session.getProjectId(), query, timeoutSeconds, maxRows, maxBytes);
            return SparqlToolResult.builder()
                    .ok(true)
                    .rows(capped.rows())
                    .truncated(capped.truncated())
                    .rowCount(capped.rows().size())
                    .revision(session.getPinnedRevision())
                    .build();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            String lower = msg.toLowerCase();
            String errorCode = (lower.contains("interrupt") || lower.contains("timeout")) ? "TIMEOUT" : "QUERY_ERROR";
            log.warn("[Assistant] run_sparql failed for session {}: {}", sessionId, msg);
            return SparqlToolResult.builder().ok(false).errorCode(errorCode).message(msg).build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SparqlToolResult {
        private boolean ok;
        private List<Map<String, String>> rows;
        private boolean truncated;
        private int rowCount;
        private Long revision;
        private Integer retrievalCallsRemaining;
        private String errorCode;
        private String message;
    }
}
