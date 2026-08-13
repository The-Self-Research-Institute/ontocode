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

@RestController
@RequestMapping("/api/swrl")
public class SwrlController {

    private static final Logger logger = LoggerFactory.getLogger(SwrlController.class);

    @Autowired
    private SwrlEngineService swrlEngineService;

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

    @PostMapping("/{projectId}/rules")
    public ResponseEntity<?> createRule(
            @PathVariable String projectId,
            @Valid @RequestBody CreateRuleRequest request) {

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

    @GetMapping("/{projectId}/rules")
    public ResponseEntity<?> getRules(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean enabled) {

        try {

            if (page < 0 || size < 1 || size > 100) {
                return ResponseEntity.badRequest()
                    .body(createErrorResponse("Invalid pagination parameters"));
            }

            Pageable pageable = PageRequest.of(page, size);
            Page<SwrlRule> rules;

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
    public ResponseEntity<?> updateRule(
            @PathVariable String projectId,
            @PathVariable String ruleId,
            @RequestBody UpdateRuleRequest request) {

        try {
            SwrlRule rule = swrlEngineService.updateRule(
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

    @DeleteMapping("/{projectId}/rules/{ruleId}")
    public ResponseEntity<?> deleteRule(
            @PathVariable String projectId,
            @PathVariable String ruleId) {

        try {
            swrlEngineService.deleteRule(ruleId);
            return ResponseEntity.noContent().build();

        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();

        } catch (Exception e) {
            logger.error("Failed to delete rule {} for project {}", ruleId, projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Failed to delete rule: " + e.getMessage()));
        }
    }

    @PostMapping("/{projectId}/execute")
    public ResponseEntity<?> executeRules(@PathVariable String projectId) {
        try {
            ExecutionResult result = swrlEngineService.executeRules(projectId);

            ExecutionResponse response = new ExecutionResponse(
                result.isSuccess(),
                result.getExecutionTimeMs(),
                result.getInferredAxiomsCount(),
                swrlEngineService.getRules(projectId).size(),
                result.getInferredAxioms(),
                result.getErrorMessage()
            );

            response.setExecutedRuleNames(result.getExecutedRuleNames());
            response.setExecutionMode(result.getExecutionMode());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Failed to execute rules for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Execution failed: " + e.getMessage()));
        }
    }

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

            response.setExecutedRuleNames(result.getExecutedRuleNames());
            response.setExecutionMode(result.getExecutionMode());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Failed to execute selected rules for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Execution failed: " + e.getMessage()));
        }
    }

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

    @PostMapping("/{projectId}/rules/batch")
    public ResponseEntity<?> createRulesBatch(
            @PathVariable String projectId,
            @RequestBody List<CreateRuleRequest> requests) {

        try {
            List<SwrlRule> createdRules = swrlEngineService.createRulesBatch(projectId, requests);
            return ResponseEntity.status(HttpStatus.CREATED).body(createdRules);

        } catch (Exception e) {
            logger.error("Failed to create rules batch for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Batch creation failed: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{projectId}/rules/batch")
    public ResponseEntity<?> deleteRulesBatch(
            @PathVariable String projectId,
            @RequestBody List<String> ruleIds) {

        try {
            swrlEngineService.deleteRulesBatch(ruleIds);
            return ResponseEntity.noContent().build();

        } catch (Exception e) {
            logger.error("Failed to delete rules batch for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Batch deletion failed: " + e.getMessage()));
        }
    }

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

    @PostMapping("/{projectId}/rules/import")
    public ResponseEntity<?> importRules(
            @PathVariable String projectId,
            @RequestBody List<CreateRuleRequest> rules) {

        try {
            ImportResult result = swrlEngineService.importRules(projectId, rules);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Failed to import rules for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Import failed: " + e.getMessage()));
        }
    }

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
    public ResponseEntity<?> duplicateRule(
            @PathVariable String projectId,
            @PathVariable String ruleId,
            @RequestBody(required = false) Map<String, String> request) {

        try {
            String newName = request != null ? request.get("newName") : null;
            SwrlRule duplicated = swrlEngineService.duplicateRule(ruleId, newName);
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

    private Map<String, Object> createErrorResponse(String message) {
        return Map.of(
            "error", true,
            "message", message,
            "timestamp", Instant.now().toString()
        );
    }

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
}