package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RuleMigrationService {

    private static final Logger log = LoggerFactory.getLogger(RuleMigrationService.class);

    public static final String ORIGIN_SOURCE = "swrl-migration";
    public static final String ORIGIN_LABEL = "Migrated saved rule";

    public interface DraftTargets {
        List<String> readyDraftUsers(String projectId);

        void addToDraft(String projectId, String userId, Set<SWRLRule> axioms) throws Exception;

        void addToBaseline(String projectId, String userId, Set<SWRLRule> axioms) throws Exception;
    }

    public record Unmigrated(String id, String ruleName, String ruleText, String error) {
    }

    public record Report(int migrated, List<String> duplicates, List<Unmigrated> unmigrated) {
    }

    private final SwrlRuleClient client;
    private final RuleService ruleService;
    private final ProjectWriteLockRegistry lockRegistry;
    private final DraftTargets draftTargets;
    private final ConcurrentHashMap<String, List<Unmigrated>> settled = new ConcurrentHashMap<>();

    @Autowired
    public RuleMigrationService(SwrlRuleClient client, RuleService ruleService, ProjectWriteLockRegistry lockRegistry,
                                @Autowired(required = false) DraftTargets draftTargets) {
        this.client = client;
        this.ruleService = ruleService;
        this.lockRegistry = lockRegistry;
        this.draftTargets = draftTargets;
    }

    public List<Unmigrated> ensureMigrated(String projectId) {
        List<Unmigrated> known = settled.get(projectId);
        if (known != null) {
            return known;
        }
        try {
            return run(projectId, false).unmigrated();
        } catch (Exception e) {
            log.warn("[RULES] Saved-rule migration for project {} could not run: {}", projectId, e.getMessage());
            return List.of();
        }
    }

    public Report migrate(String projectId) throws Exception {
        return run(projectId, true);
    }

    private Report run(String projectId, boolean force) throws Exception {
        return lockRegistry.runExclusive(projectId, () -> {
            if (!force) {
                List<Unmigrated> known = settled.get(projectId);
                if (known != null) {
                    return new Report(0, List.of(), known);
                }
            }
            Report report = migrateLocked(projectId);
            settled.put(projectId, report.unmigrated());
            return report;
        });
    }

    private Report migrateLocked(String projectId) throws Exception {
        List<Map<String, Object>> rows = client.listLegacy(projectId);
        int migrated = 0;
        List<String> duplicates = new ArrayList<>();
        List<Unmigrated> unmigrated = new ArrayList<>();
        if (rows.isEmpty()) {
            return new Report(0, duplicates, unmigrated);
        }
        OWLOntology publicOntology = ruleService.load(projectId, false, null);
        Set<String> namesThisRun = new HashSet<>();
        for (Map<String, Object> row : rows) {
            String id = text(row.get("id"));
            String name = text(row.get("ruleName"));
            String ruleText = text(row.get("ruleText"));
            if (name == null || name.isBlank() || ruleText == null || ruleText.isBlank()) {
                unmigrated.add(new Unmigrated(id, name, ruleText, "The saved rule has no name or no rule text."));
                continue;
            }
            String key = name.trim().toLowerCase(java.util.Locale.ROOT);
            if (namesThisRun.contains(key) || RuleService.hasRuleNamed(publicOntology, name)) {
                duplicates.add(name);
                client.markMigrated(projectId, id);
                continue;
            }
            RuleService.Built built;
            try {
                Object enabled = row.get("enabled");
                built = ruleService.buildNew(projectId, new RuleService.RuleInput(name, ruleText,
                        text(row.get("comment")), text(row.get("category")),
                        enabled == null ? Boolean.TRUE : Boolean.valueOf(String.valueOf(enabled))));
            } catch (RuleService.InvalidRuleException e) {
                unmigrated.add(new Unmigrated(id, name, ruleText, e.getMessage()));
                continue;
            }
            Set<SWRLRule> axioms = Set.of(built.axiom());
            ruleService.apply(projectId, axioms, false, null, "Saved rules migration",
                    ChangeOrigin.fromTool(ORIGIN_SOURCE, ORIGIN_LABEL)
                            .withRule(new ChangeOrigin.RuleNote(name, ChangeOrigin.RuleNote.MIGRATED)));
            namesThisRun.add(key);
            carryIntoDrafts(projectId, name, built.axiom());
            client.markMigrated(projectId, id);
            migrated++;
        }
        return new Report(migrated, duplicates, unmigrated);
    }

    private void carryIntoDrafts(String projectId, String name, SWRLRule axiom) {
        if (draftTargets == null) {
            return;
        }
        Set<SWRLRule> axioms = Set.of(axiom);
        for (String user : draftTargets.readyDraftUsers(projectId)) {
            try {
                OWLOntology draft = ruleService.load(projectId, true, user);
                if (!RuleService.hasRuleAxiom(draft, axiom) && !RuleService.hasRuleNamed(draft, name)) {
                    draftTargets.addToDraft(projectId, user, axioms);
                }
                draftTargets.addToBaseline(projectId, user, axioms);
            } catch (Exception e) {
                log.warn("[RULES] Could not carry migrated rule '{}' into the draft of {} in project {}: {}",
                        name, user, projectId, e.getMessage());
            }
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
