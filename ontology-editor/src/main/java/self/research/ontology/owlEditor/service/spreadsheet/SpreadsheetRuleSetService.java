package self.research.ontology.owlEditor.service.spreadsheet;

import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.SpreadsheetRuleSetEntity;
import self.research.ontology.owlEditor.repository.SpreadsheetRuleSetRepository;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleSpec;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
public class SpreadsheetRuleSetService {

    static final int MAX_NAME_LENGTH = 120;
    static final int MAX_RULES = 500;

    private final SpreadsheetRuleSetRepository repository;

    public SpreadsheetRuleSetService(SpreadsheetRuleSetRepository repository) {
        this.repository = repository;
    }

    public static class DuplicateNameException extends RuntimeException {
        public DuplicateNameException(String name) {
            super("A rule set named '" + name + "' already exists in this project");
        }
    }

    public List<SpreadsheetRuleSetEntity> list(String projectId) {
        return repository.findByProjectIdOrderByUpdatedAtDesc(projectId);
    }

    public Optional<SpreadsheetRuleSetEntity> get(String projectId, String id) {
        return repository.findById(id).filter(r -> projectId.equals(r.getProjectId()));
    }

    public SpreadsheetRuleSetEntity create(String projectId, String name, String description, List<RuleSpec> rules, String user) {
        return create(projectId, name, description, rules, user, null);
    }

    public boolean isCreatedBy(SpreadsheetRuleSetEntity ruleSet, String userId, String username) {
        if (ruleSet.getCreatedByUserId() != null && userId != null) {
            return ruleSet.getCreatedByUserId().equals(userId);
        }
        return ruleSet.getCreatedBy() != null && username != null && ruleSet.getCreatedBy().equalsIgnoreCase(username);
    }

    public SpreadsheetRuleSetEntity create(String projectId, String name, String description, List<RuleSpec> rules,
                                           String user, String userId) {
        String clean = validate(name, rules);
        if (repository.findByProjectIdAndName(projectId, clean).isPresent()) {
            throw new DuplicateNameException(clean);
        }
        SpreadsheetRuleSetEntity e = new SpreadsheetRuleSetEntity();
        e.setProjectId(projectId);
        e.setName(clean);
        e.setDescription(description);
        e.setRules(List.copyOf(rules));
        e.setCreatedBy(user);
        e.setCreatedByUserId(userId);
        e.setUpdatedBy(user);
        return repository.save(e);
    }

    public Optional<SpreadsheetRuleSetEntity> update(String projectId, String id, String name, String description,
                                                     List<RuleSpec> rules, String user) {
        String clean = validate(name, rules);
        Optional<SpreadsheetRuleSetEntity> existing = get(projectId, id);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        repository.findByProjectIdAndName(projectId, clean)
            .filter(other -> !other.getId().equals(id))
            .ifPresent(other -> {
                throw new DuplicateNameException(clean);
            });
        SpreadsheetRuleSetEntity e = existing.get();
        e.setName(clean);
        e.setDescription(description);
        e.setRules(List.copyOf(rules));
        e.setUpdatedBy(user);
        e.setUpdatedAt(new Date());
        return Optional.of(repository.save(e));
    }

    public boolean delete(String projectId, String id) {
        Optional<SpreadsheetRuleSetEntity> existing = get(projectId, id);
        existing.ifPresent(repository::delete);
        return existing.isPresent();
    }

    private static String validate(String name, List<RuleSpec> rules) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Give the rule set a name");
        }
        if (clean.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("The name is too long (at most " + MAX_NAME_LENGTH + " characters)");
        }
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("A rule set needs at least one rule");
        }
        if (rules.size() > MAX_RULES) {
            throw new IllegalArgumentException("A rule set can hold at most " + MAX_RULES + " rules");
        }
        return clean;
    }
}
