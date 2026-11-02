package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import self.research.ontology.owlEditor.service.ChangeOrigin;
import self.research.ontology.owlEditor.service.RequestActor;
import self.research.ontology.owlEditor.service.RuleImportReport;
import self.research.ontology.owlEditor.service.RuleImporter;
import self.research.ontology.owlEditor.service.RuleMigrationService;
import self.research.ontology.owlEditor.service.RuleService;
import self.research.ontology.owlEditor.service.RuleWriteAccess;
import self.research.ontology.owlEditor.service.SwrlRuleClient;
import self.research.ontology.owlEditor.service.spreadsheet.RuleImportFile;
import self.research.ontology.owlEditor.service.spreadsheet.RuleSpreadsheet;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology/{projectId}/rules")
@CrossOrigin
public class RuleImportController {

    private static final Logger log = LoggerFactory.getLogger(RuleImportController.class);
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final RuleService ruleService;

    @Autowired(required = false)
    private RuleMigrationService migrationService;

    @Autowired(required = false)
    private RuleWriteAccess writeAccess;

    public RuleImportController(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    @PostMapping("/import")
    public ResponseEntity<?> importRules(@PathVariable String projectId,
                                         @RequestParam(defaultValue = "false") boolean draft,
                                         @RequestParam(defaultValue = "false") boolean dryRun,
                                         @RequestParam("file") MultipartFile file,
                                         @RequestParam(required = false) String importId,
                                         @RequestParam(required = false) String previewStamp,
                                         @RequestParam(required = false) String userId,
                                         @RequestParam(required = false) String username,
                                         HttpServletRequest http) {
        try {
            if (file == null || file.isEmpty()) {
                return message(400, "Choose a file to import");
            }
            if (file.getSize() > RuleSpreadsheet.MAX_FILE_BYTES) {
                return message(413, "The file is larger than " + (RuleSpreadsheet.MAX_FILE_BYTES / (1024 * 1024)) + " MB");
            }
            RuleImportFile parsed = RuleSpreadsheet.read(file.getBytes(), file.getOriginalFilename());
            String actor = RequestActor.forScope(http, userId, draft);
            if (migrationService != null) {
                migrationService.ensureMigrated(projectId);
            }
            RuleImportReport report = ruleService.importRules(projectId, draft, actor,
                    RequestActor.displayName(http, username), parsed, importId, previewStamp, dryRun,
                    ChangeOrigin.manual(), () -> refusal(projectId, draft, http));
            return ResponseEntity.ok(report);
        } catch (RuleImportFile.RejectedException e) {
            return message(e.status(), e.getMessage());
        } catch (RuleService.ImportStaleException e) {
            return ResponseEntity.status(409).body(e.report());
        } catch (RuleService.ImportConflictException e) {
            return message(409, e.getMessage());
        } catch (RuleService.ImportForbiddenException e) {
            return message(403, e.getMessage());
        } catch (RuleService.ImportFailedException e) {
            return message(500, e.getMessage());
        } catch (IllegalArgumentException e) {
            return message(400, e.getMessage());
        } catch (SwrlRuleClient.UnavailableException e) {
            return message(503, e.getMessage());
        } catch (IOException e) {
            return message(400, "The file could not be read");
        } catch (Exception e) {
            log.error("[RULES] Import failed for project {}: {}", projectId, e.getMessage(), e);
            return message(500, "The rules could not be imported");
        }
    }

    private String refusal(String projectId, boolean draft, HttpServletRequest http) {
        if (draft || writeAccess == null || http == null) {
            return null;
        }
        RuleWriteAccess.Decision decision = writeAccess.decide(http.getHeader("Authorization"), http.getRemoteAddr(), projectId);
        return decision.allowed() ? null : decision.reason();
    }

    @GetMapping("/export")
    public ResponseEntity<?> export(@PathVariable String projectId, @RequestParam(defaultValue = "xlsx") String format,
                                    @RequestParam(defaultValue = "false") boolean draft,
                                    @RequestParam(required = false) String userId, HttpServletRequest http) {
        try {
            boolean json = "json".equalsIgnoreCase(format);
            if (!json && !"xlsx".equalsIgnoreCase(format)) {
                return message(400, "The format must be xlsx or json");
            }
            RuleImporter.Export data = ruleService.exportRules(projectId, draft, RequestActor.forScope(http, userId, draft));
            byte[] bytes = json ? RuleSpreadsheet.writeJson(data.rules(), data.prefixes())
                    : RuleSpreadsheet.writeXlsx(data.rules(), data.prefixes());
            String safe = projectId.replaceAll("[^A-Za-z0-9._-]", "_");
            return ResponseEntity.ok()
                    .contentType(json ? MediaType.APPLICATION_JSON : XLSX)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"swrl-rules-" + safe + (json ? ".json" : ".xlsx") + "\"")
                    .header("X-Rules-Left-Out", String.valueOf(data.leftOut()))
                    .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "X-Rules-Left-Out, Content-Disposition")
                    .body(bytes);
        } catch (SwrlRuleClient.UnavailableException e) {
            return message(503, e.getMessage());
        } catch (IllegalArgumentException e) {
            return message(400, e.getMessage());
        } catch (Exception e) {
            log.error("[RULES] Export failed for project {}: {}", projectId, e.getMessage(), e);
            return message(500, "The rules could not be exported");
        }
    }

    @GetMapping("/template.xlsx")
    public ResponseEntity<byte[]> template(@PathVariable String projectId) {
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"swrl-rules-template.xlsx\"")
                .body(RuleSpreadsheet.template());
    }

    private static ResponseEntity<?> message(int status, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", text);
        body.put("success", false);
        body.put("error", text);
        return ResponseEntity.status(status).body(body);
    }
}
