package self.research.ontology.owlEditor.controller;

import self.research.ontology.owlEditor.service.PublicEditGate;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.common.preview.PreviewStamp;
import self.research.ontology.owlEditor.service.ChangeOrigin;
import self.research.ontology.owlEditor.service.DraftNotReadyException;
import self.research.ontology.owlEditor.service.MutationPreconditionGuard;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.RequestActor;
import self.research.ontology.owlEditor.service.RuleMigrationService;
import self.research.ontology.owlEditor.service.RuleService;
import self.research.ontology.owlEditor.service.RuleWriteAccess;
import self.research.ontology.owlEditor.service.StalePreviewException;
import self.research.ontology.owlEditor.service.SwrlRuleClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@RestController
@RequestMapping("/api/ontology/{projectId}/rules")
@CrossOrigin
public class RuleController {

    @Autowired(required = false)
    private PublicEditGate publicEditGate;

    private static final Logger log = LoggerFactory.getLogger(RuleController.class);

    private final RuleService ruleService;
    private final ProjectWriteLockRegistry lockRegistry;

    @Autowired(required = false)
    private MutationPreconditionGuard preconditionGuard;

    @Autowired(required = false)
    private RuleMigrationService migrationService;

    @Autowired(required = false)
    private RuleWriteAccess writeAccess;

    public static final String DISABLED_WARNING = "Other tools ignore the disabled flag and will run these rules.";

    public static final String MANCHESTER_WARNING = "The Manchester export does not include rule names or comments, "
            + "and disabled rules would run as normal rules in other tools.";

    public static final String OBO_WARNING = "The OBO format does not support rules, so the rules are not included.";

    public RuleController(RuleService ruleService, ProjectWriteLockRegistry lockRegistry) {
        this.ruleService = ruleService;
        this.lockRegistry = lockRegistry;
    }

    public record RuleRequest(String ruleName, String ruleText, String comment, String category, Boolean enabled,
                              String userId, String username, PreviewStamp precondition,
                              OntologyCrudController.MutationOrigin origin) {
    }

    @GetMapping
    public ResponseEntity<?> list(@PathVariable String projectId,
                                  @RequestParam(defaultValue = "false") boolean draft,
                                  @RequestParam(required = false) String userId,
                                  HttpServletRequest http) {
        try {
            String actor = draft ? RequestActor.forScope(http, userId, true) : null;
            List<RuleMigrationService.Unmigrated> unmigrated = migrationService == null ? List.of()
                    : migrationService.ensureMigrated(projectId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("rules", ruleService.list(projectId, draft, actor));
            body.put("unmigrated", unmigrated);
            return ResponseEntity.ok(body);
        } catch (RuntimeException e) {
            return failure(e);
        }
    }

    @PostMapping("/migrate")
    public ResponseEntity<?> migrate(@PathVariable String projectId, HttpServletRequest http) {
        if (migrationService == null) {
            return ResponseEntity.status(503).body(Map.of("success", false, "error", "Rule migration is not available"));
        }
        if (writeAccess != null && http != null) {
            RuleWriteAccess.Decision decision = writeAccess.decide(http.getHeader("Authorization"),
                    http.getRemoteAddr(), projectId);
            if (!decision.allowed()) {
                return ResponseEntity.status(403).body(Map.of("success", false, "error", decision.reason()));
            }
        }
        try {
            RuleMigrationService.Report report = migrationService.migrate(projectId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("migrated", report.migrated());
            body.put("duplicates", report.duplicates());
            body.put("unmigrated", report.unmigrated());
            return ResponseEntity.ok(body);
        } catch (RuntimeException e) {
            return failure(e);
        } catch (Exception e) {
            log.error("[RULES] Migration failed for project {}: {}", projectId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "The saved rules could not be moved"));
        }
    }

    @GetMapping("/export-check")
    public ResponseEntity<?> exportCheck(@PathVariable String projectId,
                                         @RequestParam(defaultValue = "false") boolean draft,
                                         @RequestParam(required = false) String userId,
                                         @RequestParam(required = false) String format,
                                         HttpServletRequest http) {
        try {
            String actor = draft ? RequestActor.forScope(http, userId, true) : null;
            List<Map<String, String>> disabled = ruleService.disabledRules(projectId, draft, actor);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("disabledRules", disabled);
            body.put("warning", exportWarning(format, disabled, () -> ruleService.ruleCount(projectId, draft, actor)));
            return ResponseEntity.ok(body);
        } catch (RuntimeException e) {
            return failure(e);
        }
    }

    static String exportWarning(String format, List<Map<String, String>> disabled,
                                java.util.function.IntSupplier ruleCount) {
        String normalized = format == null ? "" : format.trim().toLowerCase(java.util.Locale.ROOT);
        boolean manchester = normalized.equals("manchester") || normalized.equals("omn");
        boolean obo = normalized.equals("obo");
        if ((manchester || obo) && ruleCount.getAsInt() > 0) {
            return manchester ? MANCHESTER_WARNING : OBO_WARNING;
        }
        return disabled.isEmpty() ? null : DISABLED_WARNING;
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable String projectId,
                                    @RequestParam(defaultValue = "false") boolean draft,
                                    @RequestBody RuleRequest request,
                                    HttpServletRequest http) {
        if (migrationService != null) {
            migrationService.ensureMigrated(projectId);
        }
        return write(projectId, draft, request, http, (actor, name, origin) ->
                ruleService.create(projectId, draft, actor, name, input(request), origin));
    }

    @PutMapping("/{ruleId}")
    public ResponseEntity<?> update(@PathVariable String projectId, @PathVariable String ruleId,
                                    @RequestParam(defaultValue = "false") boolean draft,
                                    @RequestBody RuleRequest request,
                                    HttpServletRequest http) {
        return write(projectId, draft, request, http, (actor, name, origin) ->
                ruleService.update(projectId, ruleId, draft, actor, name, input(request), origin));
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<?> delete(@PathVariable String projectId, @PathVariable String ruleId,
                                    @RequestParam(defaultValue = "false") boolean draft,
                                    @RequestParam(required = false) String userId,
                                    @RequestParam(required = false) String username,
                                    HttpServletRequest http) {
        RuleRequest request = new RuleRequest(null, null, null, null, null, userId, username, null, null);
        return write(projectId, draft, request, http, (actor, name, origin) -> {
            ruleService.delete(projectId, ruleId, draft, actor, name, origin);
            return null;
        });
    }

    private interface Write {
        Object run(String actor, String username, ChangeOrigin origin) throws Exception;
    }

    private ResponseEntity<?> write(String projectId, boolean draft, RuleRequest request, HttpServletRequest http,
                                    Write work) {
        ResponseEntity<Map<String, Object>> publicEditOff = PublicEditGate.refuse(publicEditGate, projectId, draft);
        if (publicEditOff != null) {
            return publicEditOff;
        }
        String actor = RequestActor.forScope(http, request.userId(), draft);
        String username = RequestActor.displayName(http, request.username());
        ChangeOrigin origin = request.origin() == null ? ChangeOrigin.manual()
                : ChangeOrigin.fromTool(request.origin().source(), request.origin().label());
        try {
            Callable<Object> locked = () -> {
                if (preconditionGuard != null) {
                    preconditionGuard.check(projectId, draft, actor, request.precondition());
                }
                return work.run(actor, username, origin);
            };
            Object result = lockRegistry.runExclusive(projectId, locked);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("draft", draft);
            if (result != null) {
                body.put("rule", result);
            }
            return ResponseEntity.ok(body);
        } catch (StalePreviewException e) {
            return ResponseEntity.status(409).body(Map.of("success", false, "draft", draft, "stale", true,
                    "error", e.getMessage(), "reasons", e.reasons()));
        } catch (DraftNotReadyException e) {
            return ResponseEntity.status(409).body(Map.of("success", false, "draft", draft, "error", e.getMessage(),
                    "draftCopyNotReady", true));
        } catch (RuntimeException e) {
            return failure(e);
        } catch (Exception e) {
            log.error("[RULES] Rule change failed for project {}: {}", projectId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "The rule could not be saved"));
        }
    }

    private static RuleService.RuleInput input(RuleRequest request) {
        return new RuleService.RuleInput(request.ruleName(), request.ruleText(), request.comment(), request.category(),
                request.enabled());
    }

    private static ResponseEntity<?> failure(RuntimeException e) {
        if (e instanceof RuleService.InvalidRuleException invalid) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("error", invalid.getMessage());
            body.put("errors", invalid.errors());
            body.put("suggestions", invalid.suggestions());
            return ResponseEntity.badRequest().body(body);
        }
        if (e instanceof RuleService.RuleNameTakenException) {
            return ResponseEntity.status(409).body(Map.of("success", false, "error", e.getMessage(),
                    "code", "RULE_NAME_TAKEN"));
        }
        if (e instanceof RuleService.RuleAmbiguousException) {
            return ResponseEntity.status(409).body(Map.of("success", false, "error", e.getMessage(),
                    "code", "RULE_AMBIGUOUS"));
        }
        if (e instanceof RuleService.RuleNotFoundException) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", e.getMessage()));
        }
        if (e instanceof IllegalArgumentException) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage(),
                    "errors", List.of(e.getMessage())));
        }
        if (e instanceof SwrlRuleClient.UnavailableException) {
            return ResponseEntity.status(503).body(Map.of("success", false, "error", e.getMessage()));
        }
        log.error("[RULES] Rule request failed: {}", e.getMessage(), e);
        return ResponseEntity.status(500).body(Map.of("success", false, "error", "The rule request failed"));
    }
}
