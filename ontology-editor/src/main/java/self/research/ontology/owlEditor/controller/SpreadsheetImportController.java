package self.research.ontology.owlEditor.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import self.research.ontology.owlEditor.service.DraftNotReadyException;
import self.research.ontology.owlEditor.service.SparqlQueryContext;
import self.research.ontology.owlEditor.service.spreadsheet.GenerationResult;
import self.research.ontology.owlEditor.service.spreadsheet.SpreadsheetImportService;
import self.research.ontology.owlEditor.service.spreadsheet.SpreadsheetParseException;
import self.research.ontology.owlEditor.service.spreadsheet.SpreadsheetParserService;
import self.research.ontology.owlEditor.service.spreadsheet.SpreadsheetWorkbook;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleParseException;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleParser;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleSpec;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/api/ontology/spreadsheet-import")
@CrossOrigin
public class SpreadsheetImportController {

    private static final Logger log = LoggerFactory.getLogger(SpreadsheetImportController.class);
    private static final int MAX_PREVIEW_ROWS = 1_000;
    private static final int MAX_LISTED_AXIOMS = 50_000;
    private static final int MAX_LISTED_ISSUES = 5_000;

    private final SpreadsheetParserService parserService;
    private final SpreadsheetImportService importService;
    private final ObjectMapper objectMapper;

    public SpreadsheetImportController(SpreadsheetParserService parserService,
                                       SpreadsheetImportService importService,
                                       ObjectMapper objectMapper) {
        this.parserService = parserService;
        this.importService = importService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{projectId}/imports/{importId}/items")
    public ResponseEntity<?> importedItems(@PathVariable String projectId,
                                           @PathVariable String importId,
                                           @RequestParam(defaultValue = "20") int limit,
                                           @RequestParam(required = false) String group,
                                           @RequestParam(defaultValue = "0") int offset,
                                           HttpServletRequest request) {
        String caller = SpreadsheetImportCaller.resolve(request, null, null).userId();
        int capped = Math.max(1, Math.min(limit, 200));
        if (group != null && !group.isBlank()) {
            return importService.importedItemsPage(projectId, importId, caller, group, Math.max(0, offset), capped)
                .<ResponseEntity<?>>map(page -> ResponseEntity.ok(Map.of("success", true, "data", page)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("success", false, "error", "Import or group not found")));
        }
        return importService.importedItems(projectId, importId, caller, capped)
            .<ResponseEntity<?>>map(items -> ResponseEntity.ok(Map.of("success", true, "data", items)))
            .orElseGet(() -> ResponseEntity.status(404).body(Map.of("success", false, "error", "Import not found")));
    }

    @PostMapping("/{projectId}/preview")
    public ResponseEntity<?> preview(@PathVariable String projectId,
                                     @RequestParam("file") MultipartFile file,
                                     @RequestParam("rules") String rulesJson,
                                     @RequestParam(defaultValue = "5000") int limit,
                                     @RequestParam(required = false) String userId,
                                     HttpServletRequest request) {
        useCallersDraft(SpreadsheetImportCaller.resolve(request, userId, null));
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "No file uploaded"));
        }
        List<RuleSpec> rules;
        try {
            rules = objectMapper.readValue(rulesJson, new TypeReference<List<RuleSpec>>() { });
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Invalid rules: " + e.getMessage()));
        }
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        try (InputStream in = file.getInputStream()) {
            GenerationResult result = importService.generate(projectId, in, fileName, rules == null ? List.of() : rules);
            SpreadsheetImportService.Novelty novelty = result.hasErrors() || result.ops().isEmpty()
                ? null : importService.novelty(projectId, result);
            return ResponseEntity.ok(Map.of("success", true, "data",
                toPreviewResponse(result, Math.max(0, Math.min(limit, MAX_LISTED_AXIOMS)), novelty)));
        } catch (SpreadsheetParseException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Could not read upload: " + e.getMessage()));
        }
    }

    @PostMapping("/{projectId}/commit")
    public ResponseEntity<?> commit(@PathVariable String projectId,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestParam("rules") String rulesJson,
                                    @RequestParam(defaultValue = "false") boolean draft,
                                    @RequestParam(required = false) String userId,
                                    @RequestParam(required = false) String username,
                                    HttpServletRequest request) {
        SpreadsheetImportCaller.Caller caller = SpreadsheetImportCaller.resolve(request, userId, username);
        useCallersDraft(caller);
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "No file uploaded"));
        }
        List<RuleSpec> rules;
        try {
            rules = objectMapper.readValue(rulesJson, new TypeReference<List<RuleSpec>>() { });
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Invalid rules: " + e.getMessage()));
        }
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        try (InputStream in = file.getInputStream()) {
            SpreadsheetImportService.CommitResult r = importService.commit(projectId, in, fileName,
                rules == null ? List.of() : rules, draft, caller.userId(), caller.username());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("importId", r.importId());
            data.put("axiomCount", r.axiomCount());
            data.put("triplesAdded", r.triplesAdded());
            data.put("alreadyPresent", r.alreadyPresent());
            data.put("draft", r.draft());
            return ResponseEntity.ok(Map.of("success", true, "data", data));
        } catch (SpreadsheetImportService.ImportRejectedException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("error", e.getMessage());
            body.put("preview", toPreviewResponse(e.result(), 0, null));
            return ResponseEntity.unprocessableEntity().body(body);
        } catch (SpreadsheetParseException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "The import failed: " + e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Could not read upload: " + e.getMessage()));
        } catch (RuntimeException e) {
            if (isDraftNotReady(e)) {
                return ResponseEntity.status(409).body(Map.of("success", false, "draftCopyNotReady", true,
                    "error", "Your draft is still being created. Try again in a moment."));
            }
            log.error("[SpreadsheetImport] Commit failed for project {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "The import failed: " + e.getMessage()));
        }
    }

    private static void useCallersDraft(SpreadsheetImportCaller.Caller caller) {
        if (caller.userId() != null && !caller.userId().isBlank()) {
            SparqlQueryContext.setUserId(caller.userId());
        }
    }

    private static boolean isDraftNotReady(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof DraftNotReadyException) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> toPreviewResponse(GenerationResult result, int limit,
                                                         SpreadsheetImportService.Novelty novelty) {
        Map<String, Long> byType = new TreeMap<>();
        result.axioms().forEach(a -> byType.merge(a.type(), 1L, Long::sum));
        long errors = result.issues().stream().filter(i -> i.severity() == GenerationResult.Severity.ERROR).count();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("axiomCount", result.axioms().size());
        data.put("countsByType", byType);
        data.put("rowsProcessed", result.rowsProcessed());
        data.put("errorCount", errors);
        data.put("warningCount", result.issues().size() - errors);
        boolean nothingNew = novelty != null && novelty.nothingNew();
        data.put("canCommit", !result.hasErrors() && !result.ops().isEmpty() && !nothingNew);
        data.put("triples", novelty == null ? null : novelty.triples());
        data.put("alreadyPresent", novelty == null ? null : novelty.alreadyPresent());
        data.put("nothingNew", nothingNew);
        data.put("axioms", result.axioms().subList(0, Math.min(limit, result.axioms().size())));
        data.put("axiomsTruncated", result.axioms().size() > limit);
        data.put("issues", result.issues().subList(0, Math.min(MAX_LISTED_ISSUES, result.issues().size())));
        data.put("issuesTruncated", result.issues().size() > MAX_LISTED_ISSUES);
        return data;
    }

    @PostMapping("/{projectId}/parse")
    public ResponseEntity<?> parse(@PathVariable String projectId,
                                   @RequestParam("file") MultipartFile file,
                                   @RequestParam(defaultValue = "200") int previewRows) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "No file uploaded"));
        }
        int limit = Math.max(1, Math.min(previewRows, MAX_PREVIEW_ROWS));
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        try (InputStream in = file.getInputStream()) {
            SpreadsheetWorkbook workbook = parserService.parse(in, fileName);
            log.info("[SpreadsheetImport] Parsed '{}' for project {}: {} sheet(s)",
                fileName, projectId, workbook.sheets().size());
            return ResponseEntity.ok(Map.of("success", true, "data", toResponse(workbook, limit)));
        } catch (SpreadsheetParseException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Could not read upload: " + e.getMessage()));
        }
    }

    @PostMapping("/{projectId}/rules/validate")
    public ResponseEntity<?> validateRules(@PathVariable String projectId,
                                           @RequestBody ValidateRulesRequest request) {
        List<String> rules = request == null || request.rules() == null ? List.of() : request.rules();
        List<Map<String, Object>> results = new ArrayList<>(rules.size());
        for (int i = 0; i < rules.size(); i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("index", i);
            try {
                RuleAst.TransformationRule parsed = RuleParser.parse(rules.get(i));
                r.put("valid", true);
                r.put("entityKind", parsed.kind().keyword());
            } catch (RuleParseException e) {
                r.put("valid", false);
                r.put("error", e.getMessage());
                r.put("line", e.line());
                r.put("column", e.column());
            }
            results.add(r);
        }
        return ResponseEntity.ok(Map.of("success", true, "data", results));
    }

    public record ValidateRulesRequest(List<String> rules) {
    }

    private static Map<String, Object> toResponse(SpreadsheetWorkbook workbook, int previewRows) {
        List<Map<String, Object>> sheets = new ArrayList<>();
        for (SpreadsheetWorkbook.Sheet sheet : workbook.sheets()) {
            List<String> columns = new ArrayList<>(sheet.columnCount());
            for (int c = 1; c <= sheet.columnCount(); c++) {
                columns.add(SpreadsheetParserService.columnLetter(c));
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", sheet.name());
            s.put("rowCount", sheet.rowCount());
            s.put("columnCount", sheet.columnCount());
            s.put("columns", columns);
            s.put("rows", sheet.rows().subList(0, Math.min(previewRows, sheet.rowCount())));
            s.put("truncated", sheet.rowCount() > previewRows);
            sheets.add(s);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fileName", workbook.fileName());
        data.put("format", workbook.format());
        data.put("sheets", sheets);
        return data;
    }
}
