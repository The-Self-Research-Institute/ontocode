package self.research.ontology.owlEditor.service.spreadsheet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.model.SpreadsheetImportRecord;
import self.research.ontology.owlEditor.repository.DraftChangeRepository;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;
import self.research.ontology.owlEditor.repository.SpreadsheetImportRecordRepository;

import java.util.List;

@Component
public class SpreadsheetImportDraftSupport {

    private static final Logger log = LoggerFactory.getLogger(SpreadsheetImportDraftSupport.class);

    public static final String SESSION_PREFIX = "spreadsheet-import-";

    private final SpreadsheetImportRecordRepository records;
    private final HistoryChangeRepository history;
    private final DraftChangeRepository drafts;

    public SpreadsheetImportDraftSupport(SpreadsheetImportRecordRepository records, HistoryChangeRepository history,
                                         DraftChangeRepository drafts) {
        this.records = records;
        this.history = history;
        this.drafts = drafts;
    }

    public static boolean isImportSession(String sessionId) {
        return sessionId != null && sessionId.startsWith(SESSION_PREFIX);
    }

    public void promoteDraftImports(String projectId, String userId) {
        List<SpreadsheetImportRecord> imports = records.findByProjectIdAndUserIdAndDraftTrue(projectId, userId);
        for (SpreadsheetImportRecord record : imports) {
            record.setDraft(false);
            records.save(record);
            history.markDraftEntriesShared(projectId, SpreadsheetImportService.ENTITY_IRI_PREFIX + record.getId());
        }
        if (!imports.isEmpty()) {
            log.info("[SpreadsheetImport] Published {} draft import(s) for user {} in project {}", imports.size(), userId, projectId);
        }
    }

    public void discardDraftChanges(String projectId, String userId, String sessionId) {
        if (!isImportSession(sessionId)) {
            return;
        }
        int parked = drafts.parkPending(projectId, userId, sessionId);
        log.info("[SpreadsheetImport] Parked {} pending draft changes of undone import session {}", parked, sessionId);
    }

    public void removeDraftChanges(String projectId, String userId, String sessionId) {
        if (isImportSession(sessionId)) {
            drafts.deletePending(projectId, userId, sessionId);
        }
    }

    public void restoreDraftChanges(String projectId, String userId, String sessionId) {
        if (!isImportSession(sessionId)) {
            return;
        }
        int restored = drafts.restoreParked(projectId, userId, sessionId);
        log.info("[SpreadsheetImport] Restored {} draft changes of redone import session {}", restored, sessionId);
    }
}
