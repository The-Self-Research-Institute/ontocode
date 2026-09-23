package self.research.ontology.owlEditor.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class AssistantContextToolService {

    private final AssistantSessionService sessionService;
    private final SparqlDatasetService datasetService;
    private final StorageManager storageManager;

    public AssistantContextToolService(AssistantSessionService sessionService, SparqlDatasetService datasetService,
                                        StorageManager storageManager) {
        this.sessionService = sessionService;
        this.datasetService = datasetService;
        this.storageManager = storageManager;
    }

    public ContextToolResult readContext(String sessionId, String userEmail, List<Target> targets, String kind) {
        Optional<AssistantSessionDocument> sessionOpt = sessionService.getActiveSession(sessionId, userEmail);
        if (sessionOpt.isEmpty()) {
            return ContextToolResult.builder().ok(false).errorCode("SESSION_NOT_FOUND")
                    .message("Session not found, expired, or not yours").build();
        }
        AssistantSessionDocument session = sessionOpt.get();

        if (!sessionService.tryConsumeRetrievalAttempt(sessionId)) {
            return ContextToolResult.builder().ok(false).errorCode("BUDGET_EXHAUSTED")
                    .message("Retrieval budget exhausted for this session").build();
        }

        List<Item> items = new ArrayList<>();
        boolean anyPartial = false;
        for (Target target : targets) {
            try {
                if ("range".equals(target.type())) {
                    items.add(resolveRange(session.getProjectId(), target.value()));
                } else if ("identifier".equals(target.type())) {
                    if ("diagnostics".equals(kind)) {
                        anyPartial = true;
                        log.info("[Assistant] diagnostics requested for {} — not yet backed by a real reasoner "
                                + "signal in this service, returning partial coverage rather than fake data",
                                target.value());
                        continue;
                    }
                    items.add(resolveIdentifier(session.getProjectId(), target.value(), kind));
                }
            } catch (Exception e) {
                log.warn("[Assistant] read_context target {} failed: {}", target.value(), e.getMessage());
                anyPartial = true;
            }
        }

        return ContextToolResult.builder()
                .ok(true)
                .items(items)
                .coverage(anyPartial ? "partial" : "complete")
                .revision(session.getPinnedRevision())
                .build();
    }

    private Item resolveRange(String projectId, String encodedRange) throws IOException {
        String[] formatAndRange = encodedRange.split(":", 2);
        String format = formatAndRange.length > 1 ? formatAndRange[0] : "turtle";
        String[] bounds = (formatAndRange.length > 1 ? formatAndRange[1] : formatAndRange[0]).split("-", 2);
        long startLine = Long.parseLong(bounds[0]);
        int lineCount = bounds.length > 1 ? Integer.parseInt(bounds[1]) : 50;

        StorageManager.CodeViewPage page = storageManager.readCodeViewPage(projectId, format, startLine, lineCount);
        return Item.builder()
                .source(format)
                .range(startLine + "-" + (startLine + page.lineCount()))
                .text(page.content())
                .kind("range")
                .build();
    }

    private Item resolveIdentifier(String projectId, String iri, String kind) {
        String query = "definitions".equals(kind)
                ? "SELECT ?p ?o WHERE { <" + iri + "> ?p ?o }"
                : "SELECT ?s ?p WHERE { ?s ?p <" + iri + "> }";
        SparqlDatasetService.CappedSparqlResult result =
                datasetService.execSelectCapped(projectId, query, 10, 100, 50_000);
        if (result.capExceeded() != null) {
            throw new IllegalStateException("Identifier " + iri + " has more " + kind
                    + " than the assistant's read cap allows (" + result.capExceeded() + ")");
        }

        StringBuilder text = new StringBuilder();
        for (Map<String, String> row : result.rows()) {
            row.forEach((var, value) -> text.append(var).append("=").append(value).append("; "));
            text.append("\n");
        }
        return Item.builder()
                .source(iri)
                .range(null)
                .text(text.toString())
                .kind(kind)
                .build();
    }

    public record Target(String type, String value) {}

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private String source;
        private String range;
        private String text;
        private String kind;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContextToolResult {
        private boolean ok;
        private List<Item> items;
        private String coverage;
        private Long revision;
        private String errorCode;
        private String message;
    }
}
