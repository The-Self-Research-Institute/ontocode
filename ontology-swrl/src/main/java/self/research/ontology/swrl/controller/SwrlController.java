package self.research.ontology.swrl.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.swrl.dto.*;
import self.research.ontology.swrl.model.*;
import self.research.ontology.swrl.service.SwrlEngineService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * IMPROVED VERSION - Complete implementation with better error handling
 */
@RestController
@RequestMapping("/api/swrl")
public class SwrlController {

    private static final Logger logger = LoggerFactory.getLogger(SwrlController.class);

    @Autowired
    private SwrlEngineService swrlEngineService;

    @Autowired
    private self.research.ontology.swrl.service.OntologyClientService ontologyClient;

    @Autowired
    private self.research.ontology.swrl.service.SqwrlResultStore sqwrlResults;

    @org.springframework.beans.factory.annotation.Value("${ontocode.capacity.swrl-execute.sqwrl-page-size:100}")
    private int sqwrlPageSize = 100;

    @org.springframework.beans.factory.annotation.Value("${ontocode.capacity.swrl-execute.sqwrl-max-page-size:1000}")
    private int sqwrlMaxPageSize = 1000;

    @org.springframework.beans.factory.annotation.Value("${ontocode.capacity.swrl-execute.sqwrl-max-page-bytes:8388608}")
    private long sqwrlMaxPageBytes = 8L * 1024 * 1024;

    @org.springframework.beans.factory.annotation.Value("${ontocode.internal.token:ontocode-internal}")
    private String internalToken;

    private ResponseEntity<?> denyUnlessMayWriteRules(String projectId) {
        try {
            self.research.ontology.swrl.service.OntologyClientService.RuleAccess access = ontologyClient.canWriteRules(projectId);
            if (access.allowed()) {
                return null;
            }
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(createErrorResponse(access.reason()));
        } catch (RuntimeException e) {
            logger.warn("Could not check the permission to change rules of project {}: {}", projectId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(createErrorResponse("Could not check your permission to change rules. Try again shortly."));
        }
    }

    private ResponseEntity<?> gone(String projectId) {
        return ResponseEntity.status(HttpStatus.GONE).body(createErrorResponse(
                "Rules are stored in the ontology now. Create, change, enable, disable and delete them with /api/ontology/"
                        + projectId + "/rules."));
    }

    private ResponseEntity<?> busy(self.research.ontology.swrl.service.SwrlBusyException e) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("busy", true);
        body.put("reason", e.getReason());
        body.put("retryAfterSeconds", e.getRetryAfterSeconds());
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                .body(body);
    }

    @GetMapping("/internal/execution-stats")
    public ResponseEntity<?> executionStats(
            @RequestHeader(value = "X-Ontocode-Internal-Token", required = false) String token) {
        if (!hasInternalToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(createErrorResponse("This endpoint is for internal services only"));
        }
        return ResponseEntity.ok(swrlEngineService.executionStats());
    }

    private boolean hasInternalToken(String presented) {
        return presented != null && java.security.MessageDigest.isEqual(
                presented.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                internalToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @PostMapping(value = "/infer/ontology-rules", consumes = {"text/plain", "application/octet-stream"})
    public ResponseEntity<?> inferOntologyRules(@RequestBody String ontologyText,
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-Ontocode-Internal-Token", required = false) String token) {
        if (!hasInternalToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(createErrorResponse("This endpoint is for internal services only"));
        }
        try {
            return ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                    .body(swrlEngineService.inferOntologyRules(ontologyText));
        } catch (java.util.concurrent.TimeoutException e) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(createErrorResponse("The rule engine timed out"));
        } catch (self.research.ontology.swrl.service.SwrlBusyException e) {
            return busy(e);
        } catch (Exception e) {
            logger.error("Inferring the rules of an ontology failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Rule engine failed: " + e.getMessage()));
        }
    }

    /**
     * Validate a SWRL rule
     */
    @PostMapping("/{projectId}/validate")
    public ResponseEntity<?> validateRule(
            @PathVariable String projectId,
            @RequestBody Map<String, String> request,
            @RequestParam(defaultValue = "false") boolean draft,
            @RequestParam(required = false) String userId) {
        
        try {
            String ruleText = request.get("ruleText");
            
            if (ruleText == null || ruleText.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(createErrorResponse("Rule text cannot be empty"));
            }
            
            ValidationResult result = swrlEngineService.validateRule(projectId, ruleText, draft && userId != null && !userId.isBlank() ? userId : null);
            return ResponseEntity.ok(result);
            
        } catch (self.research.ontology.swrl.service.SwrlBusyException e) {
            return busy(e);
        } catch (Exception e) {
            logger.error("Validation error for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Validation failed: " + e.getMessage()));
        }
    }

    @PostMapping("/{projectId}/rules")
    public ResponseEntity<?> createRule(@PathVariable String projectId, @RequestBody(required = false) Map<String, Object> request) {
        return gone(projectId);
    }

    @PostMapping("/{projectId}/rules/parse")
    public ResponseEntity<?> parseRule(
            @PathVariable String projectId,
            @RequestBody Map<String, String> request,
            @RequestParam(defaultValue = "false") boolean draft,
            @RequestParam(required = false) String userId) {
        String ruleText = request == null ? null : request.get("ruleText");
        if (ruleText == null || ruleText.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(createErrorResponse("Rule text cannot be empty"));
        }
        if (draft && (userId == null || userId.isBlank())) {
            return ResponseEntity.badRequest().body(createErrorResponse("userId is required to parse on a private draft"));
        }
        try {
            return ResponseEntity.ok(swrlEngineService.parseRule(projectId, ruleText, draft ? userId : null));
        } catch (self.research.ontology.swrl.service.SwrlBusyException e) {
            return busy(e);
        } catch (Exception e) {
            logger.error("Failed to parse a rule for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Parsing failed: " + e.getMessage()));
        }
    }

    @GetMapping("/{projectId}/rules/legacy")
    public ResponseEntity<?> getLegacyRules(@PathVariable String projectId) {
        try {
            return ResponseEntity.ok(swrlEngineService.legacyRules(projectId));
        } catch (Exception e) {
            logger.error("Failed to read the legacy rules of project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Failed to read the older saved rules: " + e.getMessage()));
        }
    }

    @PostMapping("/{projectId}/rules/legacy/{ruleId}/migrated")
    public ResponseEntity<?> markLegacyRuleMigrated(@PathVariable String projectId, @PathVariable String ruleId) {
        ResponseEntity<?> denied = denyUnlessMayWriteRules(projectId);
        if (denied != null) {
            return denied;
        }
        try {
            return ResponseEntity.ok(swrlEngineService.markMigrated(projectId, ruleId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(createErrorResponse(e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to mark rule {} of project {} as migrated", ruleId, projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Failed to mark the rule as migrated: " + e.getMessage()));
        }
    }

    /**
     * ✅ NEW: Get rules with pagination and filtering
     */
    @GetMapping("/{projectId}/rules")
    public ResponseEntity<?> getRules(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean enabled) {
        
        try {
            // Validate pagination parameters
            if (page < 0 || size < 1 || size > 100) {
                return ResponseEntity.badRequest()
                    .body(createErrorResponse("Invalid pagination parameters"));
            }
            
            Pageable pageable = PageRequest.of(page, size);
            List<SwrlRule> all = new java.util.ArrayList<>(swrlEngineService.listRulesAsRows(projectId, null));
            if (search != null && !search.trim().isEmpty()) {
                String needle = search.toLowerCase();
                all.removeIf(r -> !(String.valueOf(r.getRuleName()).toLowerCase().contains(needle)
                        || String.valueOf(r.getRuleText()).toLowerCase().contains(needle)));
            } else if (category != null && !category.trim().isEmpty()) {
                all.removeIf(r -> !category.equals(r.getCategory()));
            } else if (enabled != null) {
                all.removeIf(r -> r.isEnabled() != enabled);
            }
            int from = (int) Math.min(pageable.getOffset(), all.size());
            int to = Math.min(from + size, all.size());
            Page<SwrlRule> rules = new PageImpl<>(all.subList(from, to), pageable, all.size());

            return ResponseEntity.ok(rules);
            
        } catch (Exception e) {
            logger.error("Failed to get rules for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Failed to retrieve rules: " + e.getMessage()));
        }
    }

    /**
     * ✅ FIXED: Get single rule - Now implemented!
     */
    @GetMapping("/{projectId}/rules/embedded")
    public ResponseEntity<?> getEmbeddedRules(@PathVariable String projectId,
                                              @RequestParam(defaultValue = "false") boolean draft,
                                              @RequestParam(required = false) String userId) {
        try {
            return ResponseEntity.ok(swrlEngineService.listEmbeddedRules(projectId, draft ? userId : null));
        } catch (Exception e) {
            logger.error("Failed to list rules stored in the ontology for project {}", projectId, e);
            return ResponseEntity.status(500).body(createErrorResponse("Could not read the rules stored in the ontology"));
        }
    }

    @GetMapping("/{projectId}/axioms/asserted")
    public ResponseEntity<?> getAssertedAxioms(@PathVariable String projectId,
                                               @RequestParam(defaultValue = "false") boolean draft,
                                               @RequestParam(required = false) String userId,
                                               @RequestParam(required = false) String search,
                                               @RequestParam(required = false) String type,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "50") int pageSize) {
        if (draft && (userId == null || userId.isBlank())) {
            return ResponseEntity.badRequest().body(createErrorResponse("userId is required to list the axioms of a private draft"));
        }
        if (page < 0) {
            return ResponseEntity.badRequest().body(createErrorResponse("page cannot be negative"));
        }
        try {
            return ResponseEntity.ok(swrlEngineService.listAssertedAxioms(projectId, draft ? userId : null, search, type, page, pageSize));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
        } catch (self.research.ontology.swrl.service.SwrlBusyException e) {
            return busy(e);
        } catch (java.util.concurrent.TimeoutException e) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(createErrorResponse("Listing the axioms timed out"));
        } catch (self.research.ontology.swrl.service.OntologyTooLargeException e) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(createErrorResponse(e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to list the asserted axioms of project {}", projectId, e);
            return ResponseEntity.status(500).body(createErrorResponse("Could not read the axioms of the ontology"));
        }
    }

    @GetMapping(value = "/{projectId}/rules/owl")
    public ResponseEntity<?> getRulesAsOwl(@PathVariable String projectId,
                                           @RequestParam(defaultValue = "false") boolean draft,
                                           @RequestParam(required = false) String userId,
                                           @RequestHeader(value = "X-Ontocode-Internal-Token", required = false) String token) {
        if (!hasInternalToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(createErrorResponse("This endpoint is for internal services only"));
        }
        try {
            SwrlEngineService.RulesExport export = swrlEngineService.exportEnabledRulesAsOwl(projectId, draft ? userId : null);
            return ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                    .header("X-Ontocode-Skipped-Rules", skippedRulesHeader(export.skipped()))
                    .header("X-Ontocode-Rules-Fingerprint", export.fingerprint())
                    .body(export.text());
        } catch (Exception e) {
            logger.error("Failed to export the rules of project {} as OWL", projectId, e);
            return ResponseEntity.status(500).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(createErrorResponse("Could not export the rules"));
        }
    }

    static String skippedRulesHeader(List<SkippedRule> skipped) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.getFactory().configure(com.fasterxml.jackson.core.JsonGenerator.Feature.ESCAPE_NON_ASCII, true);
        int limit = 8 * 1024;
        try {
            String full = mapper.writeValueAsString(skipped);
            if (full.length() <= limit) {
                return full;
            }
            List<Object> kept = new java.util.ArrayList<>();
            int shown = 0;
            while (shown < skipped.size()) {
                kept.add(skipped.get(shown));
                String attempt = mapper.writeValueAsString(kept);
                if (attempt.length() > limit - 200) {
                    kept.remove(kept.size() - 1);
                    break;
                }
                shown++;
            }
            kept.add(Map.of("ruleName", "...", "reason", (skipped.size() - shown) + " more skipped rules not listed"));
            return mapper.writeValueAsString(kept);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "[]";
        }
    }

    @GetMapping("/{projectId}/rules/{ruleId}")
    public ResponseEntity<?> getRule(
            @PathVariable String projectId,
            @PathVariable String ruleId) {
        
        try {
            SwrlRule rule = swrlEngineService.getRule(projectId, ruleId);
            return ResponseEntity.ok(rule);
            
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
            
        } catch (Exception e) {
            logger.error("Failed to get rule {} for project {}", ruleId, projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Failed to retrieve rule: " + e.getMessage()));
        }
    }

    @PutMapping("/{projectId}/rules/{ruleId}")
    public ResponseEntity<?> updateRule(@PathVariable String projectId, @PathVariable String ruleId,
                                        @RequestBody(required = false) Map<String, Object> request) {
        return gone(projectId);
    }

    @DeleteMapping("/{projectId}/rules/{ruleId}")
    public ResponseEntity<?> deleteRule(@PathVariable String projectId, @PathVariable String ruleId) {
        return gone(projectId);
    }

    /**
     * Execute all enabled rules
     */
    @PostMapping("/{projectId}/execute")
    public ResponseEntity<?> executeRules(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "false") boolean draft,
            @RequestParam(required = false) String userId) {
        if (draft && (userId == null || userId.isBlank())) {
            return ResponseEntity.badRequest().body(createErrorResponse("userId is required to run on a private draft"));
        }
        try {
            ExecutionResult result = swrlEngineService.executeRules(projectId, draft ? userId : null);
            
            ExecutionResponse response = new ExecutionResponse(
                result.isSuccess(),
                result.getExecutionTimeMs(),
                result.getInferredAxiomsCount(),
                result.getExecutedRuleNames() == null ? 0 : result.getExecutedRuleNames().size()
                        + (result.getSkippedRules() == null ? 0 : result.getSkippedRules().size()),
                result.getInferredAxioms(),
                result.getErrorMessage()
            );
            // Pass through rule names and execution mode
            response.setExecutedRuleNames(result.getExecutedRuleNames());
            response.setExecutedRuleIds(result.getExecutedRuleIds());
            response.setExecutionMode(result.getExecutionMode());
            response.setStamp(result.getStamp());
            response.setSkippedRules(result.getSkippedRules());
            response.setConclusionCount(result.getConclusionCount());
            response.setStructureCount(result.getStructureCount());
            response.setOmittedTrivialCount(result.getOmittedTrivialCount());
            response.setTruncated(result.isTruncated());
            response.setCompletion(result.getCompletion());
            
            return ResponseEntity.ok(response);
            
        } catch (self.research.ontology.swrl.service.SwrlBusyException e) {
            return busy(e);
        } catch (Exception e) {
            logger.error("Failed to execute rules for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Execution failed: " + e.getMessage()));
        }
    }

    /**
     * ✅ NEW: Execute selected rules by IDs
     */
    @PostMapping("/{projectId}/execute/selected")
    public ResponseEntity<?> executeSelectedRules(
            @PathVariable String projectId,
            @RequestBody Map<String, List<String>> request) {
        
        try {
            List<String> ruleIds = request.get("ruleIds");
            if (ruleIds == null || ruleIds.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(createErrorResponse("Rule IDs are required"));
            }
            
            ExecutionResult result = swrlEngineService.executeSelectedRules(projectId, ruleIds);
            
            ExecutionResponse response = new ExecutionResponse(
                result.isSuccess(),
                result.getExecutionTimeMs(),
                result.getInferredAxiomsCount(),
                ruleIds.size(),
                result.getInferredAxioms(),
                result.getErrorMessage()
            );
            // Pass through rule names and execution mode
            response.setExecutedRuleNames(result.getExecutedRuleNames());
            response.setExecutedRuleIds(result.getExecutedRuleIds());
            response.setExecutionMode(result.getExecutionMode());
            response.setSkippedRules(result.getSkippedRules());
            response.setConclusionCount(result.getConclusionCount());
            response.setStructureCount(result.getStructureCount());
            response.setOmittedTrivialCount(result.getOmittedTrivialCount());
            response.setTruncated(result.isTruncated());
            response.setCompletion(result.getCompletion());
            
            return ResponseEntity.ok(response);
            
        } catch (self.research.ontology.swrl.service.SwrlBusyException e) {
            return busy(e);
        } catch (Exception e) {
            logger.error("Failed to execute selected rules for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Execution failed: " + e.getMessage()));
        }
    }

    /**
     * ✅ NEW: Test a single rule (dry run)
     */
    @PostMapping("/{projectId}/rules/{ruleId}/test")
    public ResponseEntity<?> testRule(
            @PathVariable String projectId,
            @PathVariable String ruleId) {
        
        try {
            ExecutionResult result = swrlEngineService.testSingleRuleById(projectId, ruleId);
            return ResponseEntity.ok(result);
            
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
            
        } catch (self.research.ontology.swrl.service.SwrlBusyException e) {
            return busy(e);
        } catch (Exception e) {
            logger.error("Failed to test rule {} for project {}", ruleId, projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Test failed: " + e.getMessage()));
        }
    }

    @PostMapping("/{projectId}/rules/batch")
    public ResponseEntity<?> createRulesBatch(@PathVariable String projectId, @RequestBody(required = false) Object request) {
        return gone(projectId);
    }

    @DeleteMapping("/{projectId}/rules/batch")
    public ResponseEntity<?> deleteRulesBatch(@PathVariable String projectId, @RequestBody(required = false) Object request) {
        return gone(projectId);
    }

    @GetMapping("/{projectId}/rules/export")
    public ResponseEntity<?> exportRules(@PathVariable String projectId) {
        try {
            List<SwrlRule> rules = swrlEngineService.listRulesAsRows(projectId, null);
            return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=rules-" + projectId + ".json")
                .body(rules);

        } catch (Exception e) {
            logger.error("Failed to export rules for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Export failed: " + e.getMessage()));
        }
    }

    @PostMapping("/{projectId}/rules/import")
    public ResponseEntity<?> importRules(@PathVariable String projectId, @RequestBody(required = false) Object request) {
        return gone(projectId);
    }

    /**
     * ✅ NEW: Get rule statistics
     */
    @GetMapping("/{projectId}/rules/stats")
    public ResponseEntity<?> getRuleStats(@PathVariable String projectId) {
        try {
            RuleStatistics stats = swrlEngineService.getRuleStatistics(projectId);
            return ResponseEntity.ok(stats);
            
        } catch (Exception e) {
            logger.error("Failed to get stats for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Failed to get statistics: " + e.getMessage()));
        }
    }

    @PostMapping("/{projectId}/rules/{ruleId}/duplicate")
    public ResponseEntity<?> duplicateRule(@PathVariable String projectId, @PathVariable String ruleId,
                                           @RequestBody(required = false) Map<String, String> request) {
        return gone(projectId);
    }

    /**
     * Clear cache for a project
     */
    @PostMapping("/{projectId}/cache/clear")
    public ResponseEntity<?> clearCache(@PathVariable String projectId) {
        try {
            swrlEngineService.clearCache(projectId);
            return ResponseEntity.ok(Map.of(
                "message", "Cache cleared successfully",
                "projectId", projectId,
                "timestamp", Instant.now()
            ));
            
        } catch (Exception e) {
            logger.error("Failed to clear cache for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Failed to clear cache: " + e.getMessage()));
        }
    }

    /**
     * ✅ NEW: Get cache statistics
     */
    @GetMapping("/{projectId}/cache/stats")
    public ResponseEntity<?> getCacheStats(@PathVariable String projectId) {
        try {
            Map<String, Object> stats = swrlEngineService.getCacheStats();
            return ResponseEntity.ok(stats);
            
        } catch (Exception e) {
            logger.error("Failed to get cache stats", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Failed to get cache statistics: " + e.getMessage()));
        }
    }

    /**
     * ✅ IMPROVED: Helper to create standardized error responses
     */
    private Map<String, Object> createErrorResponse(String message) {
        return Map.of(
            "error", true,
            "message", message,
            "timestamp", Instant.now().toString()
        );
    }

    /**
     * Execute a SQWRL query and return tabular results
     */
    @PostMapping("/{projectId}/sqwrl/query")
    public ResponseEntity<?> executeSqwrlQuery(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> request) {
        
        try {
            String queryText = (String) request.get("queryText");
            String queryName = (String) request.get("queryName");
            Integer maxResults = request.get("maxResults") != null 
                ? ((Number) request.get("maxResults")).intValue() 
                : null;
            
            if (queryText == null || queryText.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(createErrorResponse("Query text is required"));
            }
            
            logger.info("Executing SQWRL query for project {}: {}", projectId, queryText);
            
            SwrlEngineService.SqwrlQueryResult result = swrlEngineService.executeSqwrlQuery(
                projectId, queryText, queryName, maxResults);
            
            if (result.isSuccess()) {
                int pageSize = clampPageSize(request.get("pageSize") instanceof Number n ? n.intValue() : null);
                List<String> columns = result.getColumnNames() != null ? result.getColumnNames() : List.of();
                List<Map<String, String>> all = result.getRows() != null ? result.getRows() : List.of();
                self.research.ontology.swrl.service.SqwrlResultStore.Meta meta = new self.research.ontology.swrl.service.SqwrlResultStore.Meta(
                    result.getQueryName(), result.getQueryText(), result.getExecutionTimeMs(), result.getTotalRows(),
                    result.isTotalRowsExact(), result.isTruncated(), result.getRowLimit(), result.isBytesCapped());
                String owner = self.research.ontology.swrl.service.OwnerContext.current();
                java.util.Optional<self.research.ontology.swrl.service.SqwrlResultStore.Entry> stored =
                    sqwrlResults.put(projectId, owner, columns, all, meta);
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("success", true);
                body.put("queryName", result.getQueryName());
                body.put("queryText", result.getQueryText());
                body.put("executionTimeMs", result.getExecutionTimeMs());
                body.put("columnNames", columns);
                body.put("totalRows", result.getTotalRows());
                body.put("totalRowsExact", result.isTotalRowsExact());
                body.put("truncated", result.isTruncated());
                body.put("rowLimit", result.getRowLimit());
                body.put("bytesCapped", result.isBytesCapped());
                if (stored.isPresent()) {
                    self.research.ontology.swrl.service.SqwrlPager.Page page = self.research.ontology.swrl.service.SqwrlPager.page(
                        stored.get(), null, null, false, 0, pageSize, sqwrlMaxPageBytes);
                    body.put("rows", page.rows());
                    body.put("rowCount", page.rows().size());
                    body.put("runId", stored.get().id());
                    body.put("pageSize", pageSize);
                    body.put("storedRows", all.size());
                    body.put("filteredRows", page.filteredRows());
                    body.put("nextOffset", page.nextOffset());
                    body.put("pageBytesCapped", page.pageBytesCapped());
                } else {
                    body.put("rows", all);
                    body.put("rowCount", all.size());
                }
                return ResponseEntity.ok(body);
            } else {
                return ResponseEntity.ok(Map.of(
                    "success", false,
                    "queryName", result.getQueryName() != null ? result.getQueryName() : "",
                    "queryText", result.getQueryText() != null ? result.getQueryText() : "",
                    "errorMessage", result.getErrorMessage() != null ? result.getErrorMessage() : "Unknown error"
                ));
            }
            
        } catch (self.research.ontology.swrl.service.SwrlBusyException e) {
            return busy(e);
        } catch (Exception e) {
            logger.error("SQWRL query failed for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("SQWRL query failed: " + e.getMessage()));
        }
    }

    private int clampPageSize(Integer requested) {
        int max = Math.max(1, sqwrlMaxPageSize);
        int size = requested != null && requested > 0 ? requested : sqwrlPageSize;
        return Math.min(Math.max(1, size), max);
    }

    @GetMapping("/{projectId}/sqwrl/runs/{runId}/rows")
    public ResponseEntity<?> sqwrlRows(
            @PathVariable String projectId,
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "asc") String dir,
            @RequestParam(defaultValue = "false") boolean export) {
        java.util.Optional<self.research.ontology.swrl.service.SqwrlResultStore.Entry> found =
            sqwrlResults.find(runId, projectId, self.research.ontology.swrl.service.OwnerContext.current());
        if (found.isEmpty()) {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("expired", true);
            body.put("message", "This result has expired. Run the query again.");
            return ResponseEntity.status(HttpStatus.GONE).body(body);
        }
        self.research.ontology.swrl.service.SqwrlResultStore.Entry entry = found.get();
        int effective = export ? Math.max(1, entry.rows().size()) : clampPageSize(limit);
        self.research.ontology.swrl.service.SqwrlPager.Page page = self.research.ontology.swrl.service.SqwrlPager.page(
            entry, filter, sort, "desc".equalsIgnoreCase(dir), offset, effective, sqwrlMaxPageBytes);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("runId", runId);
        body.put("columnNames", entry.columns());
        body.put("rows", page.rows());
        body.put("rowCount", page.rows().size());
        body.put("offset", page.offset());
        body.put("nextOffset", page.nextOffset());
        body.put("filteredRows", page.filteredRows());
        body.put("storedRows", entry.rows().size());
        body.put("pageBytesCapped", page.pageBytesCapped());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{projectId}/explain")
    public ResponseEntity<?> explainConclusion(
            @PathVariable String projectId,
            @RequestBody ExplainRequest request,
            @RequestParam(defaultValue = "false") boolean draft,
            @RequestParam(required = false) String userId) {
        if (draft && (userId == null || userId.isBlank())) {
            return ResponseEntity.badRequest().body(createErrorResponse("userId is required to explain on a private draft"));
        }
        try {
            return ResponseEntity.ok(swrlEngineService.explainConclusion(projectId, request, draft ? userId : null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
        } catch (self.research.ontology.swrl.service.SwrlBusyException e) {
            return busy(e);
        } catch (Exception e) {
            logger.error("Failed to explain conclusion for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Explanation failed: " + e.getMessage()));
        }
    }

    @PostMapping("/{projectId}/rules/{ruleId}/why-not")
    public ResponseEntity<?> whyNotFired(
            @PathVariable String projectId,
            @PathVariable String ruleId,
            @RequestBody WhyNotRequest request,
            @RequestParam(defaultValue = "false") boolean draft,
            @RequestParam(required = false) String userId) {
        if (draft && (userId == null || userId.isBlank())) {
            return ResponseEntity.badRequest().body(createErrorResponse("userId is required to check on a private draft"));
        }
        try {
            return ResponseEntity.ok(swrlEngineService.whyNotFired(projectId, ruleId, request, draft ? userId : null));
        } catch (IllegalArgumentException e) {
            HttpStatus status = e.getMessage() != null && e.getMessage().startsWith("Rule not found")
                ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(createErrorResponse(e.getMessage()));
        } catch (self.research.ontology.swrl.service.SwrlBusyException e) {
            return busy(e);
        } catch (Exception e) {
            logger.error("Failed to check why rule {} did not fire for project: {}", ruleId, projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Check failed: " + e.getMessage()));
        }
    }
}
