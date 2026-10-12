package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ChangeSetBroadcaster {

    public static final String EVENT_TYPE = "CHANGE_SET_APPLIED";

    private final HistorySyncService historySync;
    private final SimpMessagingTemplate messaging;

    @Autowired
    public ChangeSetBroadcaster(HistorySyncService historySync,
                                @Autowired(required = false) @Nullable SimpMessagingTemplate messaging) {
        this.historySync = historySync;
        this.messaging = messaging;
    }

    public void changeSetApplied(String projectId, String userId, String username, ChangeOrigin origin) {
        if (messaging == null || origin == null || origin.changeSetId() == null) {
            return;
        }
        try {
            List<HistoryChange> entries = historySync.getChangeSet(projectId, origin.changeSetId());
            if (entries.isEmpty()) {
                return;
            }
            Map<String, Object> event = new HashMap<>();
            event.put("type", EVENT_TYPE);
            event.put("projectId", projectId);
            event.put("changeSetId", origin.changeSetId());
            event.put("source", origin.source());
            event.put("description", describe(entries, origin.source()));
            event.put("userId", userId);
            if (userId != null && userId.contains("@")) {
                event.put("userEmail", userId);
            }
            event.put("username", username);
            event.put("timestamp", System.currentTimeMillis());
            messaging.convertAndSend("/topic/ontology/" + projectId, event);
        } catch (Exception e) {
            log.warn("[CHANGESET] Could not broadcast change set {} for project {}: {}",
                    origin.changeSetId(), projectId, e.getMessage());
        }
    }

    static String describe(List<HistoryChange> entries, String source) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (HistoryChange entry : entries) {
            if (entry.getEntityIRI() == null || entry.getEntityIRI().isBlank()) {
                continue;
            }
            counts.merge(bucket(entry.getOperationType()), 1, Integer::sum);
        }
        String where = ChangeOrigin.AI.equals(source) ? " with Ask AI" : " in Code View";
        if (counts.isEmpty()) {
            return ChangeOrigin.AI.equals(source) ? "Applied an Ask AI edit" : "Saved Code View changes";
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((bucket, count) -> parts.add(phrase(bucket, count)));
        String joined = parts.size() == 1 ? parts.get(0)
                : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
        return Character.toUpperCase(joined.charAt(0)) + joined.substring(1) + where;
    }

    private static String bucket(String operationType) {
        String op = operationType == null ? "" : operationType;
        String verb = op.startsWith("create") || op.startsWith("add") && op.contains("Individual") ? "added"
                : op.startsWith("delete") ? "deleted" : "changed";
        String noun = verb.equals("changed") ? "entity"
                : op.contains("Class") ? "class"
                : op.contains("Property") ? "property"
                : op.contains("Individual") ? "individual" : "entity";
        return verb + ":" + noun;
    }

    private static String phrase(String bucket, int count) {
        String[] parts = bucket.split(":");
        String noun = parts[1];
        String plural = switch (noun) {
            case "class" -> "classes";
            case "property" -> "properties";
            case "entity" -> "entities";
            default -> noun + "s";
        };
        return parts[0] + " " + count + " " + (count == 1 ? noun : plural);
    }
}
