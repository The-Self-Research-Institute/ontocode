package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.util.PerfPhases;
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
import self.research.ontology.owlEditor.util.SparqlSafety;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    private static StorageManager.ContentScope scopeFor(AssistantSessionDocument session) {
        return session.isDraft() ? new StorageManager.ContentScope(true, session.getDraftUserId())
                                 : StorageManager.ContentScope.publicScope();
    }

    private ContextToolResult readAdmitted(String sessionId, AssistantSessionDocument session,
                                           List<Target> targets, String kind) {
        if ("guidance".equals(kind)) {
            return resolveGuidance(sessionId, session, targets);
        }
        if (!sessionService.tryConsumeRetrievalAttempt(sessionId)) {
            return ContextToolResult.builder().ok(false).errorCode("BUDGET_EXHAUSTED")
                    .message("Retrieval budget exhausted for this session").build();
        }

        StorageManager.ContentScope scope = scopeFor(session);
        SparqlQueryContext.setUserId(scope.userId());
        SparqlQueryContext.setWantsDraft(scope.draft());
        try {
            return readAdmittedInScope(sessionId, session, targets, kind, scope);
        } finally {
            SparqlQueryContext.clear();
        }
    }

    private ContextToolResult readAdmittedInScope(String sessionId, AssistantSessionDocument session,
                                                  List<Target> targets, String kind,
                                                  StorageManager.ContentScope scope) {
        PerfPhases perf = new PerfPhases();
        long lockRequestedAt = System.nanoTime();
        Optional<TargetResolution> readResult;
        try {
            readResult = lockRegistry.runShared(session.getProjectId(), () -> {
                perf.add("lockWait", (System.nanoTime() - lockRequestedAt) / 1_000_000);
                TargetResolution read = resolveTargets(session.getProjectId(), dedupeTargets(targets), kind, perf, scope);
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
        log.info("[Assistant] [PERF] read_context project={} session={} kind={} targets={} items={} partial={} {}",
                session.getProjectId(), sessionId, kind, targets == null ? 0 : targets.size(),
                resolution.items().size(), resolution.anyPartial(), perf.summary());

        int estimatedTokens = AssistantTokenEstimator.estimate(concatenatedText(resolution.items()));
        if (!sessionService.tryConsumeTokenBudget(sessionId, estimatedTokens)) {
            return ContextToolResult.builder().ok(false).errorCode("BUDGET_EXHAUSTED")
                    .message("Retrieval token budget exhausted for this session").build();
        }

        AssistantSessionService.BudgetSnapshot budget =
                sessionService.currentBudgetSnapshot(sessionId, session.getUserEmail()).orElse(null);
        return ContextToolResult.builder()
                .ok(true)
                .items(resolution.items())
                .coverage(resolution.anyPartial() ? "partial" : "complete")
                .revision(session.getPinnedRevision())
                .retrievalAttemptsRemaining(budget == null ? null : budget.retrievalAttemptsRemaining())
                .tokenBudgetRemaining(budget == null ? null : budget.tokenBudgetRemaining())
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

    private TargetResolution resolveTargets(String projectId, List<Target> targets, String kind, PerfPhases perf,
                                            StorageManager.ContentScope scope) {
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
            long targetStart = System.nanoTime();
            try {
                if (TYPE_STATEMENT.equals(target.type())) {
                    AssistantSourceContextReader.SourceRead read = sourceReader.statements(projectId, target.value(), scope);
                    items.addAll(read.items());
                    anyPartial |= read.partial();
                } else if ("diagnostics".equals(kind)) {
                    diagnosticTargets.add(target);
                } else if (TYPE_RANGE.equals(target.type())) {
                    anyPartial |= addRange(projectId, target.value(), items, scope);
                } else {
                    items.add(resolveIdentifier(projectId, target.value(), kind, scope));
                }
            } catch (Exception e) {
                log.warn("[Assistant] read_context target {} failed: {}", target.value(), e.getMessage());
                anyPartial = true;
                items.add(Item.builder().kind("note").text("NOTE: Could not read target \"" + target.value()
                        + "\" (" + e.getMessage() + ") — try a different range or a narrower question.").build());
            }
            if (!"diagnostics".equals(kind) || TYPE_STATEMENT.equals(target.type())) {
                perf.add(target.type(), (System.nanoTime() - targetStart) / 1_000_000);
            }
        }
        if ("diagnostics".equals(kind) && (!diagnosticTargets.isEmpty() || targets.isEmpty())) {
            long diagnosticsStart = System.nanoTime();
            try {
                AssistantSourceContextReader.SourceRead read = sourceReader.diagnostics(projectId, diagnosticTargets, scope);
                items.addAll(read.items());
                anyPartial |= read.partial();
                perf.add("diagnostics", (System.nanoTime() - diagnosticsStart) / 1_000_000);
            } catch (Exception e) {
                log.warn("[Assistant] read_context diagnostics failed for project {}: {}", projectId, e.getMessage());
                anyPartial = true;
                items.add(Item.builder().kind("note").text("NOTE: Could not read diagnostics ("
                        + e.getMessage() + ") — try again or ask a narrower question.").build());
            }
        }
        return new TargetResolution(items, anyPartial);
    }

    private static final Map<String, String> GUIDANCE_TOPICS = buildGuidanceTopics();

    private static Map<String, String> buildGuidanceTopics() {
        Map<String, String> topics = new LinkedHashMap<>();
        topics.put("prefixes", "Prefixes are stored in Mongo per project and tracked separately for the draft graph and the " +
                "public graph — editing a prefix in draft mode never changes what public viewers see until the draft is " +
                "published, and the Active Ontology panel and Code View always reflect the scope (draft or public) the " +
                "session is currently in, not a mix of both.");
        topics.put("drafts", "Every project has one public graph and, per user, an optional draft graph that starts as a " +
                "copy-on-write snapshot of the public graph. Edits in draft mode only ever touch that user's own draft — " +
                "other users and the public view are unaffected until the draft is explicitly published or discarded.");
        topics.put("delete", "Before proposing to delete or remove an identifier, check every place it's used first (read_context " +
                "with kind \"references\") and include all of them in the same edit group — a group that deletes a declaration " +
                "but leaves another part of the document still pointing at it will fail validation and have to be redone.");
        topics.put("rename", "Always use propose_rename for renaming an identifier instead of editing occurrences by hand — it " +
                "finds and rewrites every occurrence itself, including ones you might not think to search for.");
        topics.put("swrl", "SWRL rules are added with add_swrl_rule but do nothing on their own — nothing is inferred until " +
                "run_swrl_rule actually executes the ontology's enabled rules and returns the new facts.");
        topics.put("consistency", "check_consistency is cheap and usually fast; call it before relying on the ontology being " +
                "logically sound, and only call the slower explain_inconsistency afterward if it reports consistent: false.");
        return topics;
    }

    private ContextToolResult resolveGuidance(String sessionId, AssistantSessionDocument session, List<Target> targets) {
        List<Item> items = new ArrayList<>();
        boolean anyPartial = false;
        List<Target> deduped = dedupeTargets(targets);
        if (deduped.isEmpty()) {
            anyPartial = true;
            items.add(unknownGuidanceTopicNote(""));
        }
        for (Target target : deduped) {
            String topic = target.value() == null ? "" : target.value().trim().toLowerCase(Locale.ROOT);
            String text = GUIDANCE_TOPICS.get(topic);
            if (text != null) {
                items.add(Item.builder().source(topic).kind("guidance").text(text).build());
            } else {
                anyPartial = true;
                items.add(unknownGuidanceTopicNote(topic));
            }
        }
        AssistantSessionService.BudgetSnapshot budget =
                sessionService.currentBudgetSnapshot(sessionId, session.getUserEmail()).orElse(null);
        return ContextToolResult.builder()
                .ok(true)
                .items(items)
                .coverage(anyPartial ? "partial" : "complete")
                .revision(session.getPinnedRevision())
                .retrievalAttemptsRemaining(budget == null ? null : budget.retrievalAttemptsRemaining())
                .tokenBudgetRemaining(budget == null ? null : budget.tokenBudgetRemaining())
                .build();
    }

    private Item unknownGuidanceTopicNote(String topic) {
        return Item.builder().kind("note").text("NOTE: No guidance topic \"" + topic + "\". Valid topics: "
                + String.join(", ", GUIDANCE_TOPICS.keySet()) + ".").build();
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

    private boolean addRange(String projectId, String encodedRange, List<Item> items,
                             StorageManager.ContentScope scope) throws IOException {
        AssistantSourceContextReader.RangeSpec range = AssistantSourceContextReader.parseRange(encodedRange);
        StorageManager.CodeViewPage page = scope.draft()
                ? storageManager.resolveCodeViewPage(projectId, range.format(), range.startLine(), range.lineCount(), scope)
                : storageManager.readCodeViewPage(projectId, range.format(), range.startLine(), range.lineCount());
        items.add(Item.builder()
                .source(range.format())
                .range(range.startLine() + "-" + page.lineCount())
                .text(page.content())
                .kind("range")
                .build());
        return range.clamped();
    }

    private static final int DEFINITIONS_ROW_CAP = 10;
    private static final int REFERENCES_ROW_CAP = 50;

    private Item resolveIdentifier(String projectId, String iri, String kind, StorageManager.ContentScope scope) {
        boolean isReferences = "references".equals(kind);
        String safe = SparqlSafety.safeIri(iri);
        String query = "definitions".equals(kind)
                ? "SELECT ?p ?o WHERE { <" + safe + "> ?p ?o }"
                : "SELECT ?s ?p WHERE { ?s ?p <" + safe + "> }";
        SparqlDatasetService.CappedSparqlResult result = datasetService.execSelectCapped(
                projectId, query, isReferences ? REFERENCES_ROW_CAP : DEFINITIONS_ROW_CAP, 100, 50_000);
        if (result.capExceeded() != null) {
            throw new IllegalStateException("Identifier " + iri + " has more " + kind
                    + " than the assistant's read cap allows (" + result.capExceeded() + ")");
        }

        StringBuilder text = new StringBuilder();
        for (Map<String, String> row : result.rows()) {
            row.forEach((var, value) -> text.append(var).append("=").append(value).append("; "));
            if (isReferences && row.get("s") != null) {
                appendLineLocation(projectId, row.get("s"), scope, text);
            }
            text.append("\n");
        }
        return Item.builder()
                .source(iri)
                .range(null)
                .text(text.toString())
                .kind(kind)
                .build();
    }

    private void appendLineLocation(String projectId, String subjectIri, StorageManager.ContentScope scope, StringBuilder text) {
        try {
            AssistantSourceContextReader.SourceRead read = sourceReader.statements(projectId, subjectIri, scope);
            read.items().stream().filter(item -> item.getRange() != null).findFirst()
                    .ifPresent(item -> text.append("(line ").append(item.getRange()).append(") "));
        } catch (Exception e) {
            log.debug("[Assistant] Could not resolve a line location for {}: {}", subjectIri, e.getMessage());
        }
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
        private Integer retrievalAttemptsRemaining;
        private Integer tokenBudgetRemaining;
        private String errorCode;
        private String message;
        private Integer retryAfterSeconds;
    }
}
