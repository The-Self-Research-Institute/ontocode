package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.model.HistoryChange;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class OperationGroups {

    private static final Logger log = LoggerFactory.getLogger(OperationGroups.class);

    private final MongoTemplate mongoTemplate;

    public OperationGroups(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public void tag(String projectId, String userId, LocalDateTime since, String groupId) {
        try {
            mongoTemplate.updateMulti(Query.query(Criteria.where("projectId").is(projectId).and("userId").is(userId)
                            .and("draft").is(false).and("timestamp").gte(since).and("operationGroupId").is(null)),
                    new Update().set("operationGroupId", groupId), HistoryChange.class);
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
        List<HistoryChange> siblings = mongoTemplate.find(Query.query(Criteria.where("operationGroupId")
                .is(entry.getOperationGroupId()).and("reverted").is(false)), HistoryChange.class);
        for (HistoryChange sibling : siblings) {
            if (sibling.getId() == null || sibling.getId().equals(entry.getId())
                    || entry.getProjectId().equals(sibling.getProjectId())) {
                continue;
            }
            notes.add("Project " + sibling.getProjectId() + " was changed together with this edit and still has its own change in place");
        }
        return notes;
    }
}
