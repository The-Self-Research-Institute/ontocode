package self.research.ontology.swrl.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.swrl.dto.*;
import self.research.ontology.swrl.model.*;
import self.research.ontology.swrl.service.SwrlEngineService;

import jakarta.validation.Valid;
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
            @RequestBody Map<String, String> request) {
        
        try {
            String ruleText = request.get("ruleText");
            
            if (ruleText == null || ruleText.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(createErrorResponse("Rule text cannot be empty"));
            }
            
            ValidationResult result = swrlEngineService.validateRule(projectId, ruleText);
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("Validation error for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Validation failed: " + e.getMessage()));
        }
    }

    /**
     * Create a new SWRL rule
     */
    @PostMapping("/{projectId}/rules")
    public ResponseEntity<?> createRule(
            @PathVariable String projectId,
            @Valid @RequestBody CreateRuleRequest request) {
        ResponseEntity<?> denied = denyUnlessMayWriteRules(projectId);
        if (denied != null) {
            return denied;
        }
        
        try {
            SwrlRule rule = swrlEngineService.createRule(
                projectId, 
                request.getRuleName(), 
                request.getRuleText(),
                request.getComment(),
                request.getCategory()
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(rule);
            
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid rule creation request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                .body(createErrorResponse(e.getMessage()));
                
        } catch (Exception e) {
            logger.error("Failed to create rule for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Failed to create rule: " + e.getMessage()));
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
            Page<SwrlRule> rules;
            
            // Apply filters
            if (search != null && !search.trim().isEmpty()) {
                rules = swrlEngineService.searchRules(projectId, search, pageable);
            } else if (category != null && !category.trim().isEmpty()) {
                rules = swrlEngineService.getRulesByCategory(projectId, category, pageable);
            } else if (enabled != null) {
                rules = swrlEngineService.getRulesByEnabled(projectId, enabled, pageable);
            } else {
                rules = swrlEngineService.getRulesPaginated(projectId, pageable);
            }
            
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

    @GetMapping(value = "/{projectId}/rules/owl", produces = "text/plain")
    public ResponseEntity<?> getRulesAsOwl(@PathVariable String projectId,
                                           @RequestParam(defaultValue = "false") boolean draft,
                                           @RequestParam(required = false) String userId,
                                           @RequestHeader(value = "X-Ontocode-Internal-Token", required = false) String token) {
        if (!hasInternalToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(createErrorResponse("This endpoint is for internal services only"));
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
            return ResponseEntity.status(500).body(createErrorResponse("Could not export the rules"));
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

    /**
     * Update an existing rule
     */
    @PutMapping("/{projectId}/rules/{ruleId}")
    public ResponseEntity<?> updateRule(
            @PathVariable String projectId,
            @PathVariable String ruleId,
            @RequestBody UpdateRuleRequest request) {
        ResponseEntity<?> denied = denyUnlessMayWriteRules(projectId);
        if (denied != null) {
            return denied;
        }
        
        try {
            SwrlRule rule = swrlEngineService.updateRule(
                projectId,
                ruleId,
                request.getRuleText(),
                request.getComment(),
                request.getEnabled(),
                request.getCategory()
            );
            return ResponseEntity.ok(rule);
            
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid rule update: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(createErrorResponse(e.getMessage()));
                
        } catch (Exception e) {
            logger.error("Failed to update rule {} for project {}", ruleId, projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Failed to update rule: " + e.getMessage()));
        }
    }

    /**
     * Delete a rule
     */
    @DeleteMapping("/{projectId}/rules/{ruleId}")
    public ResponseEntity<?> deleteRule(
            @PathVariable String projectId,
            @PathVariable String ruleId) {
        ResponseEntity<?> denied = denyUnlessMayWriteRules(projectId);
        if (denied != null) {
            return denied;
        }
        
        try {
            swrlEngineService.deleteRule(projectId, ruleId);
            return ResponseEntity.noContent().build();
            
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
            
        } catch (Exception e) {
            logger.error("Failed to delete rule {} for project {}", ruleId, projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Failed to delete rule: " + e.getMessage()));
        }
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
                swrlEngineService.getRules(projectId).size(),
                result.getInferredAxioms(),
                result.getErrorMessage()
            );
            // Pass through rule names and execution mode
            response.setExecutedRuleNames(result.getExecutedRuleNames());
            response.setExecutionMode(result.getExecutionMode());
            response.setStamp(result.getStamp());
            response.setSkippedRules(result.getSkippedRules());
            response.setConclusionCount(result.getConclusionCount());
            response.setStructureCount(result.getStructureCount());
            response.setOmittedTrivialCount(result.getOmittedTrivialCount());
            response.setTruncated(result.isTruncated());
            response.setCompletion(result.getCompletion());
            
            return ResponseEntity.ok(response);
            
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
            response.setExecutionMode(result.getExecutionMode());
            response.setSkippedRules(result.getSkippedRules());
            response.setConclusionCount(result.getConclusionCount());
            response.setStructureCount(result.getStructureCount());
            response.setOmittedTrivialCount(result.getOmittedTrivialCount());
            response.setTruncated(result.isTruncated());
            response.setCompletion(result.getCompletion());
            
            return ResponseEntity.ok(response);
            
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
            
        } catch (Exception e) {
            logger.error("Failed to test rule {} for project {}", ruleId, projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Test failed: " + e.getMessage()));
        }
    }

    /**
     * ✅ NEW: Batch create rules
     */
    @PostMapping("/{projectId}/rules/batch")
    public ResponseEntity<?> createRulesBatch(
            @PathVariable String projectId,
            @RequestBody List<CreateRuleRequest> requests) {
        ResponseEntity<?> denied = denyUnlessMayWriteRules(projectId);
        if (denied != null) {
            return denied;
        }
        
        try {
            List<SwrlRule> createdRules = swrlEngineService.createRulesBatch(projectId, requests);
            return ResponseEntity.status(HttpStatus.CREATED).body(createdRules);
            
        } catch (Exception e) {
            logger.error("Failed to create rules batch for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Batch creation failed: " + e.getMessage()));
        }
    }

    /**
     * ✅ NEW: Batch delete rules
     */
    @DeleteMapping("/{projectId}/rules/batch")
    public ResponseEntity<?> deleteRulesBatch(
            @PathVariable String projectId,
            @RequestBody List<String> ruleIds) {
        ResponseEntity<?> denied = denyUnlessMayWriteRules(projectId);
        if (denied != null) {
            return denied;
        }
        
        try {
            swrlEngineService.deleteRulesBatch(projectId, ruleIds);
            return ResponseEntity.noContent().build();
            
        } catch (Exception e) {
            logger.error("Failed to delete rules batch for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Batch deletion failed: " + e.getMessage()));
        }
    }

    /**
     * ✅ NEW: Export rules
     */
    @GetMapping("/{projectId}/rules/export")
    public ResponseEntity<?> exportRules(@PathVariable String projectId) {
        try {
            List<SwrlRule> rules = swrlEngineService.getRules(projectId);
            return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=rules-" + projectId + ".json")
                .body(rules);
                
        } catch (Exception e) {
            logger.error("Failed to export rules for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Export failed: " + e.getMessage()));
        }
    }

    /**
     * ✅ NEW: Import rules
     */
    @PostMapping("/{projectId}/rules/import")
    public ResponseEntity<?> importRules(
            @PathVariable String projectId,
            @RequestBody List<CreateRuleRequest> rules) {
        ResponseEntity<?> denied = denyUnlessMayWriteRules(projectId);
        if (denied != null) {
            return denied;
        }
        
        try {
            ImportResult result = swrlEngineService.importRules(projectId, rules);
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("Failed to import rules for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Import failed: " + e.getMessage()));
        }
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

    /**
     * ✅ NEW: Duplicate a rule
     */
    @PostMapping("/{projectId}/rules/{ruleId}/duplicate")
    public ResponseEntity<?> duplicateRule(
            @PathVariable String projectId,
            @PathVariable String ruleId,
            @RequestBody(required = false) Map<String, String> request) {
        ResponseEntity<?> denied = denyUnlessMayWriteRules(projectId);
        if (denied != null) {
            return denied;
        }
        
        try {
            String newName = request != null ? request.get("newName") : null;
            SwrlRule duplicated = swrlEngineService.duplicateRule(projectId, ruleId, newName);
            return ResponseEntity.status(HttpStatus.CREATED).body(duplicated);
            
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(createErrorResponse(e.getMessage()));
                
        } catch (Exception e) {
            logger.error("Failed to duplicate rule {} for project {}", ruleId, projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Duplication failed: " + e.getMessage()));
        }
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
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "queryName", result.getQueryName(),
                    "queryText", result.getQueryText(),
                    "executionTimeMs", result.getExecutionTimeMs(),
                    "rowCount", result.getRowCount(),
                    "columnNames", result.getColumnNames() != null ? result.getColumnNames() : List.of(),
                    "rows", result.getRows() != null ? result.getRows() : List.of()
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                    "success", false,
                    "queryName", result.getQueryName() != null ? result.getQueryName() : "",
                    "queryText", result.getQueryText() != null ? result.getQueryText() : "",
                    "errorMessage", result.getErrorMessage() != null ? result.getErrorMessage() : "Unknown error"
                ));
            }
            
        } catch (Exception e) {
            logger.error("SQWRL query failed for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("SQWRL query failed: " + e.getMessage()));
        }
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
        } catch (Exception e) {
            logger.error("Failed to check why rule {} did not fire for project: {}", ruleId, projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Check failed: " + e.getMessage()));
        }
    }
}
