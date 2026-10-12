package self.research.ontology.owlEditor.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.util.AssistantTokenEstimator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AssistantFuzzyToolService {

    private static final String FUZZY_PREFIX = "http://fuzzy.org/ontology#";
    private static final Pattern WHERE_CLAUSE = Pattern.compile("WHERE\\s+(.+?)(?:\\bORDER\\b|\\bLIMIT\\b|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ORDER_BY = Pattern.compile("ORDER\\s+BY\\s+(\\w+)\\s+(ASC|DESC)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT_CLAUSE = Pattern.compile("(?:TOP|LIMIT)\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEMBER_OF = Pattern.compile("memberOf\\(([^)]+)\\)\\s*([><=]+)\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE);

    private final AssistantSessionService sessionService;
    private final AssistantAdmissionLimiter admissionLimiter;
    private final SparqlDatasetService datasetService;

    @Value("${assistant.fuzzy.timeout-seconds:15}")
    private int timeoutSeconds;

    @Value("${assistant.fuzzy.max-rows:5000}")
    private int maxRows;

    @Value("${assistant.fuzzy.max-bytes:500000}")
    private long maxBytes;

    public AssistantFuzzyToolService(AssistantSessionService sessionService,
                                      AssistantAdmissionLimiter admissionLimiter,
                                      SparqlDatasetService datasetService) {
        this.sessionService = sessionService;
        this.admissionLimiter = admissionLimiter;
        this.datasetService = datasetService;
    }

    public FuzzyToolResult runQuery(String sessionId, String userEmail, String query) {
        Optional<AssistantSessionDocument> sessionOpt = sessionService.getActiveSession(sessionId, userEmail);
        if (sessionOpt.isEmpty()) {
            return FuzzyToolResult.builder().ok(false).errorCode("SESSION_NOT_FOUND")
                    .message("Session not found, expired, or not yours").build();
        }
        AssistantSessionDocument session = sessionOpt.get();

        if (sessionService.isRevisionStale(session)) {
            return FuzzyToolResult.builder().ok(false).errorCode("REVISION_STALE")
                    .message("The project has changed since this session's snapshot was pinned. "
                            + "Start a new request to get a fresh snapshot before reading further.")
                    .build();
        }

        AssistantAdmissionLimiter.ToolAdmission admission =
                admissionLimiter.tryAcquireTool(userEmail, session.getProjectId());
        if (admission instanceof AssistantAdmissionLimiter.Rejected rejected) {
            return FuzzyToolResult.builder().ok(false).errorCode("RATE_LIMITED")
                    .message("Too many assistant tool calls are running (" + rejected.limit()
                            + " limit). Retry in " + rejected.retryAfterSeconds() + "s.")
                    .retryAfterSeconds(rejected.retryAfterSeconds())
                    .build();
        }
        try (AssistantAdmissionLimiter.Admitted ignored = (AssistantAdmissionLimiter.Admitted) admission) {
            return runAdmitted(sessionId, session, query);
        }
    }

    private FuzzyToolResult runAdmitted(String sessionId, AssistantSessionDocument session, String query) {
        if (!sessionService.tryConsumeRetrievalAttempt(sessionId)) {
            return FuzzyToolResult.builder().ok(false).errorCode("BUDGET_EXHAUSTED")
                    .message("Retrieval budget exhausted for this session").build();
        }

        try {
            List<Map<String, Object>> individuals = evaluate(session.getProjectId(), query);
            int estimatedTokens = AssistantTokenEstimator.estimate(String.valueOf(individuals));
            if (!sessionService.tryConsumeTokenBudget(sessionId, estimatedTokens)) {
                return FuzzyToolResult.builder().ok(false).errorCode("BUDGET_EXHAUSTED")
                        .message("Retrieval token budget exhausted for this session").build();
            }
            AssistantSessionService.BudgetSnapshot budget =
                    sessionService.currentBudgetSnapshot(sessionId, session.getUserEmail()).orElse(null);
            return FuzzyToolResult.builder().ok(true)
                    .data(Map.of("individuals", individuals, "count", individuals.size()))
                    .revision(session.getPinnedRevision())
                    .retrievalAttemptsRemaining(budget == null ? null : budget.retrievalAttemptsRemaining())
                    .tokenBudgetRemaining(budget == null ? null : budget.tokenBudgetRemaining())
                    .build();
        } catch (FuzzyQueryException e) {
            return FuzzyToolResult.builder().ok(false).errorCode(e.errorCode).message(e.getMessage()).build();
        }
    }

    private List<Map<String, Object>> evaluate(String projectId, String query) throws FuzzyQueryException {
        String trimmed = query.trim();
        if (!trimmed.toUpperCase().matches("^(FIND|SELECT|GET).*")) {
            throw new FuzzyQueryException("INVALID_QUERY", "Query must start with FIND, SELECT, or GET.");
        }

        Matcher whereMatcher = WHERE_CLAUSE.matcher(trimmed);
        if (!whereMatcher.find()) {
            throw new FuzzyQueryException("INVALID_QUERY", "A WHERE clause is required.");
        }
        String condition = whereMatcher.group(1).trim();

        if (condition.toLowerCase().contains("exists(") || condition.toLowerCase().contains("forall(")) {
            throw new FuzzyQueryException("UNSUPPORTED_QUERY",
                    "This tool only supports memberOf(...) conditions today, not exists()/forall(). "
                            + "Ask the user to check the fuzzy editor's query tab for that.");
        }

        Matcher memberOfMatcher = MEMBER_OF.matcher(condition);
        if (!memberOfMatcher.find()) {
            throw new FuzzyQueryException("INVALID_QUERY",
                    "Expected a memberOf(ClassName) >= threshold condition.");
        }
        String conceptExprText = memberOfMatcher.group(1).trim();
        String operator = memberOfMatcher.group(2);
        double threshold;
        try {
            threshold = Double.parseDouble(memberOfMatcher.group(3));
        } catch (NumberFormatException e) {
            throw new FuzzyQueryException("INVALID_QUERY",
                    "'" + memberOfMatcher.group(3) + "' is not a valid threshold number.");
        }

        Map<String, Map<String, Double>> degreesByIndividual = new LinkedHashMap<>();
        Map<String, String> localNameToUri = new LinkedHashMap<>();
        fetchMemberships(projectId, degreesByIndividual, localNameToUri);

        ConceptExpr expr = parseConceptExpression(conceptExprText, localNameToUri);

        List<Map.Entry<String, Double>> matches = new ArrayList<>();
        for (Map.Entry<String, Map<String, Double>> entry : degreesByIndividual.entrySet()) {
            double degree = evaluateExpr(expr, entry.getValue());
            if (satisfiesThreshold(degree, operator, threshold)) {
                matches.add(Map.entry(entry.getKey(), degree));
            }
        }

        Matcher orderMatcher = ORDER_BY.matcher(trimmed);
        if (orderMatcher.find() && "degree".equalsIgnoreCase(orderMatcher.group(1))) {
            boolean ascending = "ASC".equalsIgnoreCase(orderMatcher.group(2));
            matches.sort(ascending ? Map.Entry.comparingByValue() : Map.Entry.<String, Double>comparingByValue().reversed());
        }

        int limit = 100;
        Matcher limitMatcher = LIMIT_CLAUSE.matcher(trimmed);
        if (limitMatcher.find()) {
            try {
                limit = Integer.parseInt(limitMatcher.group(1));
            } catch (NumberFormatException e) {
                throw new FuzzyQueryException("INVALID_QUERY",
                        "'" + limitMatcher.group(1) + "' is not a valid TOP/LIMIT number.");
            }
        }
        if (matches.size() > limit) {
            matches = matches.subList(0, limit);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<String, Double> m : matches) {
            results.add(Map.of("uri", m.getKey(), "degree", m.getValue()));
        }
        return results;
    }

    private void fetchMemberships(String projectId, Map<String, Map<String, Double>> degreesByIndividual,
                                   Map<String, String> localNameToUri) {
        String sparql = "SELECT ?entity ?class ?degree WHERE { "
                + "?entity <" + FUZZY_PREFIX + "hasMembership> ?membership . "
                + "?membership <" + FUZZY_PREFIX + "inClass> ?class ; "
                + "<" + FUZZY_PREFIX + "degree> ?degree . }";
        SparqlDatasetService.CappedSparqlResult result =
                datasetService.execSelectCapped(projectId, sparql, timeoutSeconds, maxRows, maxBytes);

        for (Map<String, String> row : result.rows()) {
            String entity = row.get("entity");
            String classUri = row.get("class");
            String degreeStr = row.get("degree");
            if (entity == null || classUri == null || degreeStr == null) {
                continue;
            }
            double degree;
            try {
                degree = Double.parseDouble(degreeStr);
            } catch (NumberFormatException e) {
                continue;
            }
            degreesByIndividual.computeIfAbsent(entity, k -> new LinkedHashMap<>()).put(classUri, degree);

            int hashIdx = classUri.lastIndexOf('#');
            int slashIdx = classUri.lastIndexOf('/');
            int splitIdx = Math.max(hashIdx, slashIdx);
            String localName = splitIdx >= 0 ? classUri.substring(splitIdx + 1) : classUri;
            localNameToUri.put(localName, classUri);
        }
    }

    private sealed interface ConceptExpr permits Atomic, Conjunction, Disjunction, Negation {}
    private record Atomic(String uri) implements ConceptExpr {}
    private record Conjunction(List<ConceptExpr> parts) implements ConceptExpr {}
    private record Disjunction(List<ConceptExpr> parts) implements ConceptExpr {}
    private record Negation(ConceptExpr inner) implements ConceptExpr {}

    private ConceptExpr parseConceptExpression(String expr, Map<String, String> localNameToUri) {
        if (expr.contains(" AND ")) {
            List<ConceptExpr> parts = new ArrayList<>();
            for (String part : expr.split(" AND ")) {
                parts.add(parseConceptExpression(part.trim(), localNameToUri));
            }
            return new Conjunction(parts);
        }
        if (expr.contains(" OR ")) {
            List<ConceptExpr> parts = new ArrayList<>();
            for (String part : expr.split(" OR ")) {
                parts.add(parseConceptExpression(part.trim(), localNameToUri));
            }
            return new Disjunction(parts);
        }
        if (expr.startsWith("NOT ")) {
            return new Negation(parseConceptExpression(expr.substring(4).trim(), localNameToUri));
        }
        String resolved = localNameToUri.getOrDefault(expr, expr);
        return new Atomic(resolved);
    }

    private double evaluateExpr(ConceptExpr expr, Map<String, Double> degrees) {
        if (expr instanceof Atomic a) {
            return degrees.getOrDefault(a.uri(), 0.0);
        }
        if (expr instanceof Conjunction c) {
            double acc = 1.0;
            for (ConceptExpr part : c.parts()) {
                acc *= evaluateExpr(part, degrees);
            }
            return acc;
        }
        if (expr instanceof Disjunction d) {
            double acc = 0.0;
            for (ConceptExpr part : d.parts()) {
                double v = evaluateExpr(part, degrees);
                acc = acc + v - acc * v;
            }
            return acc;
        }
        if (expr instanceof Negation n) {
            return 1.0 - evaluateExpr(n.inner(), degrees);
        }
        return 0.0;
    }

    private boolean satisfiesThreshold(double degree, String operator, double threshold) {
        return switch (operator) {
            case ">=" -> degree >= threshold;
            case ">" -> degree > threshold;
            case "<=" -> degree <= threshold;
            case "<" -> degree < threshold;
            case "=", "==" -> Math.abs(degree - threshold) < 1e-9;
            default -> false;
        };
    }

    private static final class FuzzyQueryException extends Exception {
        private final String errorCode;

        private FuzzyQueryException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FuzzyToolResult {
        private boolean ok;
        private Map<String, Object> data;
        private Long revision;
        private Integer retrievalAttemptsRemaining;
        private Integer tokenBudgetRemaining;
        private String errorCode;
        private String message;
        private Integer retryAfterSeconds;
    }
}
