package self.research.ontology.owlEditor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for syncing GraphDB history changes to MongoDB.
 * Enables collaboration features (approve, reject, comments) on GraphDB changes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistorySyncService {

    private final HistoryChangeRepository historyChangeRepository;
    private final GraphDBHistoryService graphDBHistoryService;

    /**
     * Sync a single change from GraphDB to MongoDB.
     * Called automatically when a change is recorded in GraphDB.
     */
    public void syncChange(String projectId, String editId, Map<String, Object> changeData) {
        // Check if already synced
        if (historyChangeRepository.existsByProjectIdAndEditId(projectId, editId)) {
            log.debug("Change {} already synced, skipping", editId);
            return;
        }

        try {
            String userId = (String) changeData.get("userId");
            String username = (String) changeData.get("username");
            
            HistoryChange.Builder builder = new HistoryChange.Builder(projectId, editId, userId, username);
            
            // Map GraphDB fields to MongoDB
            if (changeData.containsKey("operationType")) {
                builder.operationType((String) changeData.get("operationType"));
            }
            
            if (changeData.containsKey("entityType")) {
                builder.entityType((String) changeData.get("entityType"));
            }
            
            if (changeData.containsKey("entityIRI")) {
                builder.entityIRI((String) changeData.get("entityIRI"));
            }
            
            if (changeData.containsKey("entityLabel")) {
                builder.entityLabel((String) changeData.get("entityLabel"));
            }
            
            if (changeData.containsKey("oldValue")) {
                builder.oldValue((String) changeData.get("oldValue"));
            }
            
            if (changeData.containsKey("newValue")) {
                builder.newValue((String) changeData.get("newValue"));
            }
            
            if (changeData.containsKey("description")) {
                builder.description((String) changeData.get("description"));
            }
            
            // Convert timestamp if available
            if (changeData.containsKey("timestamp")) {
                Object timestampObj = changeData.get("timestamp");
                if (timestampObj instanceof Long) {
                    LocalDateTime timestamp = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli((Long) timestampObj),
                        ZoneId.systemDefault()
                    );
                    builder.timestamp(timestamp);
                }
            }
            
            HistoryChange historyChange = builder.build();
            historyChangeRepository.save(historyChange);
            
            log.info("Synced change {} to MongoDB for project {}", editId, projectId);
        } catch (Exception e) {
            log.error("Failed to sync change {} to MongoDB", editId, e);
        }
    }

    /**
     * Sync recent changes from GraphDB to MongoDB for a project.
     * Useful for initial sync or catching up after downtime.
     */
    public void syncRecentChanges(String projectId, int count) {
        try {
            List<Map<String, Object>> recentChanges = graphDBHistoryService.getHistory(projectId, count);
            
            int syncedCount = 0;
            for (Map<String, Object> change : recentChanges) {
                String editId = (String) change.get("editId");
                if (editId != null && !historyChangeRepository.existsByProjectIdAndEditId(projectId, editId)) {
                    syncChange(projectId, editId, change);
                    syncedCount++;
                }
            }
            
            log.info("Synced {} changes for project {} (out of {} recent)", syncedCount, projectId, recentChanges.size());
        } catch (Exception e) {
            log.error("Failed to sync recent changes for project {}", projectId, e);
        }
    }

    /**
     * Get all synced history changes for a project.
     */
    public List<HistoryChange> getHistoryChanges(String projectId) {
        return historyChangeRepository.findByProjectIdOrderByTimestampDesc(projectId);
    }

    /**
     * Get synced changes by status (PENDING, APPROVED, REJECTED).
     */
    public List<HistoryChange> getHistoryChangesByStatus(String projectId, String status) {
        return historyChangeRepository.findByProjectIdAndStatusOrderByTimestampDesc(projectId, status);
    }

    /**
     * Get a specific history change by ID.
     */
    public HistoryChange getHistoryChange(String changeId) {
        return historyChangeRepository.findById(changeId).orElse(null);
    }

    /**
     * Approve a change.
     */
    public boolean approveChange(String changeId, String userId, String username) {
        HistoryChange change = historyChangeRepository.findById(changeId).orElse(null);
        if (change == null) {
            return false;
        }
        
        change.setStatus("APPROVED");
        change.setApprovedBy(username);
        change.setApprovedAt(LocalDateTime.now());
        historyChangeRepository.save(change);
        
        log.info("Change {} approved by {}", changeId, username);
        return true;
    }

    /**
     * Reject a change.
     */
    public boolean rejectChange(String changeId, String userId, String username) {
        HistoryChange change = historyChangeRepository.findById(changeId).orElse(null);
        if (change == null) {
            return false;
        }
        
        change.setStatus("REJECTED");
        change.setRejectedBy(username);
        change.setRejectedAt(LocalDateTime.now());
        historyChangeRepository.save(change);
        
        log.info("Change {} rejected by {}", changeId, username);
        return true;
    }

    /**
     * Add a comment to a change.
     */
    public boolean addComment(String changeId, String userId, String username, String text) {
        HistoryChange change = historyChangeRepository.findById(changeId).orElse(null);
        if (change == null) {
            return false;
        }
        
        String commentId = UUID.randomUUID().toString();
        HistoryChange.CommentEntry comment = new HistoryChange.CommentEntry(userId, username, text);
        change.getComments().put(commentId, comment);
        historyChangeRepository.save(change);
        
        log.info("Comment added to change {} by {}", changeId, username);
        return true;
    }

    /**
     * Resolve a conflict.
     */
    public boolean resolveConflict(String changeId, String userId, String username, String resolution) {
        HistoryChange change = historyChangeRepository.findById(changeId).orElse(null);
        if (change == null) {
            return false;
        }
        
        change.setHasConflict(false);
        change.setConflictResolution(resolution);
        change.setResolvedBy(username);
        change.setResolvedAt(LocalDateTime.now());
        historyChangeRepository.save(change);
        
        log.info("Conflict resolved for change {} by {}", changeId, username);
        return true;
    }

    /**
     * Get changes with conflicts.
     */
    public List<HistoryChange> getConflicts(String projectId) {
        return historyChangeRepository.findByProjectIdAndHasConflictOrderByTimestampDesc(projectId, true);
    }
}
