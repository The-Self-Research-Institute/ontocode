package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class OperationGroups {

    private static final Logger log = LoggerFactory.getLogger(OperationGroups.class);

    private final HistoryChangeRepository history;

    public OperationGroups(HistoryChangeRepository history) {
        this.history = history;
    }

    public void tag(String projectId, String userId, LocalDateTime since, String groupId) {
        try {
            history.tagOperationGroup(projectId, userId, since, groupId);
        } catch (RuntimeException e) {
            log.warn("[UNDO] Could not group the changes of project {}: {}", projectId, e.getMessage());
        }
    }

    public String warning(List<HistoryChange> entries) {
        Set<String> notes = new LinkedHashSet<>();
        for (HistoryChange entry : entries) {
            if (entry.getOperationGroupId() == null) {
                continue;
            }
            try {
                notes.addAll(notesFor(entry));
            } catch (RuntimeException e) {
                log.debug("[UNDO] Could not look up the related changes of {}: {}", entry.getId(), e.getMessage());
            }
        }
        return notes.isEmpty() ? null : String.join(". ", notes);
    }

    private Set<String> notesFor(HistoryChange entry) {
        Set<String> notes = new LinkedHashSet<>();
        for (HistoryChange sibling : history.findOpenByOperationGroupId(entry.getOperationGroupId())) {
            if (sibling.getId() == null || sibling.getId().equals(entry.getId())
                    || entry.getProjectId().equals(sibling.getProjectId())) {
                continue;
            }
            notes.add("Project " + sibling.getProjectId() + " was changed together with this edit and still has its own change in place");
        }
        return notes;
    }
}
