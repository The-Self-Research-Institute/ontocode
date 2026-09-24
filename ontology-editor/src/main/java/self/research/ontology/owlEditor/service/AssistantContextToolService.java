package self.research.ontology.owlEditor.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.util.AssistantTokenEstimator;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class AssistantContextToolService {

    static final Duration DIAGNOSTICS_TIMEOUT = Duration.ofSeconds(20);
    static final String TYPE_RANGE = "range";
    static final String TYPE_IDENTIFIER = "identifier";
    static final String TYPE_STATEMENT = "statement";
    static final Set<String> TARGET_TYPES = Set.of(TYPE_RANGE, TYPE_IDENTIFIER, TYPE_STATEMENT);

    private final AssistantSessionService sessionService;
    private final SparqlDatasetService datasetService;
    private final StorageManager storageManager;
    private final ProjectWriteLockRegistry lockRegistry;
    private final AssistantAdmissionLimiter admissionLimiter;
    private final AssistantSourceContextReader sourceReader;

    public AssistantContextToolService(AssistantSessionService sessionService, SparqlDatasetService datasetService,
                                        StorageManager storageManager, ProjectWriteLockRegistry lockRegistry,
                                        AssistantAdmissionLimiter admissionLimiter) {
        this.sessionService = sessionService;
        this.datasetService = datasetService;
        this.storageManager = storageManager;
        this.lockRegistry = lockRegistry;
        this.admissionLimiter = admissionLimiter;
        this.sourceReader = new AssistantSourceContextReader(storageManager, DIAGNOSTICS_TIMEOUT);
    }

    public ContextToolResult readContext(String sessionId, String userEmail, List<Target> targets, String kind) {
        Optional<AssistantSessionDocument> sessionOpt = sessionService.getActiveSession(sessionId, userEmail);
        if (sessionOpt.isEmpty()) {
            return ContextToolResult.builder().ok(false).errorCode("SESSION_NOT_FOUND")
                    .message("Session not found, expired, or not yours").build();
        }
        AssistantSessionDocument session = sessionOpt.get();

        if (sessionService.isRevisionStale(session)) {
            return revisionStale();
        }

        AssistantAdmissionLimiter.ToolAdmission admission =
                admissionLimiter.tryAcquireTool(userEmail, session.getProjectId());
        if (admission instanceof AssistantAdmissionLimiter.Rejected rejected) {
            return rateLimited(rejected);
        }
        try (AssistantAdmissionLimiter.Admitted ignored = (AssistantAdmissionLimiter.Admitted) admission) {
            return readAdmitted(sessionId, session, targets, kind);
        }
    }

    private ContextToolResult readAdmitted(String sessionId, AssistantSessionDocument session,
                                           List<Target> targets, String kind) {
        if (!sessionService.tryConsumeRetrievalAttempt(sessionId)) {
            return ContextToolResult.builder().ok(false).errorCode("BUDGET_EXHAUSTED")
                    .message("Retrieval budget exhausted for this session").build();
        }

        Optional<TargetResolution> readResult;
        try {
            readResult = lockRegistry.runShared(session.getProjectId(), () -> {
                TargetResolution read = resolveTargets(session.getProjectId(), dedupeTargets(targets), kind);
                return sessionService.isRevisionStale(session) ? Optional.empty() : Optional.of(read);
            });
        } catch (Exception e) {
            log.warn("[Assistant] read_context failed for session {}: {}", sessionId, e.getMessage());
            return ContextToolResult.builder().ok(false).errorCode("QUERY_ERROR")
                    .message(e.getMessage() != null ? e.getMessage() : "read_context failed").build();
        }
        if (readResult.isEmpty()) {
            return revisionStale();
        }
        TargetResolution resolution = readResult.get();

        int estimatedTokens = AssistantTokenEstimator.estimate(concatenatedText(resolution.items()));
        if (!sessionService.tryConsumeTokenBudget(sessionId, estimatedTokens)) {
            return ContextToolResult.builder().ok(false).errorCode("BUDGET_EXHAUSTED")
                    .message("Retrieval token budget exhausted for this session").build();
        }

        return ContextToolResult.builder()
                .ok(true)
                .items(resolution.items())
                .coverage(resolution.anyPartial() ? "partial" : "complete")
                .revision(session.getPinnedRevision())
                .build();
    }

    private record TargetResolution(List<Item> items, boolean anyPartial) {}

    private ContextToolResult rateLimited(AssistantAdmissionLimiter.Rejected rejected) {
        return ContextToolResult.builder().ok(false).errorCode("RATE_LIMITED")
                .message("Too many assistant tool calls are running (" + rejected.limit()
                        + " limit). Retry in " + rejected.retryAfterSeconds() + "s.")
                .retryAfterSeconds(rejected.retryAfterSeconds())
                .build();
    }

    private ContextToolResult revisionStale() {
        return ContextToolResult.builder().ok(false).errorCode("REVISION_STALE")
                .message("The project has changed since this session's snapshot was pinned. "
                        + "Start a new request to get a fresh snapshot before reading further.").build();
    }

    private TargetResolution resolveTargets(String projectId, List<Target> targets, String kind) {
        List<Item> items = new ArrayList<>();
        boolean anyPartial = false;
        List<Target> diagnosticTargets = new ArrayList<>();
        for (Target target : targets) {
            if (target == null || target.type() == null || !TARGET_TYPES.contains(target.type())
                    || target.value() == null || target.value().isBlank()) {
                anyPartial = true;
                items.add(Item.builder().kind("note").text("NOTE: Skipped a target that needs a type of range, "
                        + "identifier or statement and a non-empty value.").build());
                continue;
            }
            try {
                if (TYPE_STATEMENT.equals(target.type())) {
                    AssistantSourceContextReader.SourceRead read = sourceReader.statements(projectId, target.value());
                    items.addAll(read.items());
                    anyPartial |= read.partial();
                } else if ("diagnostics".equals(kind)) {
                    diagnosticTargets.add(target);
                } else if (TYPE_RANGE.equals(target.type())) {
                    anyPartial |= addRange(projectId, target.value(), items);
                } else {
                    items.add(resolveIdentifier(projectId, target.value(), kind));
                }
            } catch (Exception e) {
                log.warn("[Assistant] read_context target {} failed: {}", target.value(), e.getMessage());
                anyPartial = true;
            }
        }
        if ("diagnostics".equals(kind) && (!diagnosticTargets.isEmpty() || targets.isEmpty())) {
            try {
                AssistantSourceContextReader.SourceRead read = sourceReader.diagnostics(projectId, diagnosticTargets);
                items.addAll(read.items());
                anyPartial |= read.partial();
            } catch (Exception e) {
                log.warn("[Assistant] read_context diagnostics failed for project {}: {}", projectId, e.getMessage());
                anyPartial = true;
            }
        }
        return new TargetResolution(items, anyPartial);
    }

    private List<Target> dedupeTargets(List<Target> targets) {
        List<Target> deduped = new ArrayList<>();
        if (targets == null) {
            return deduped;
        }
        Set<Target> seen = new HashSet<>();
        for (Target target : targets) {
            if (seen.add(target)) {
                deduped.add(target);
            }
        }
        return deduped;
    }

    private String concatenatedText(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item item : items) {
            if (item.getText() != null) {
                sb.append(item.getText());
            }
        }
        return sb.toString();
    }

    private boolean addRange(String projectId, String encodedRange, List<Item> items) throws IOException {
        AssistantSourceContextReader.RangeSpec range = AssistantSourceContextReader.parseRange(encodedRange);
        StorageManager.CodeViewPage page = storageManager.readCodeViewPage(
                projectId, range.format(), range.startLine(), range.lineCount());
        items.add(Item.builder()
                .source(range.format())
                .range(range.startLine() + "-" + page.lineCount())
                .text(page.content())
                .kind("range")
                .build());
        return range.clamped();
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
        private Integer retryAfterSeconds;
    }
}
