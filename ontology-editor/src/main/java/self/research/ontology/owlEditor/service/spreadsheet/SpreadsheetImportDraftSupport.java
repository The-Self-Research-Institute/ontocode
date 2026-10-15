package self.research.ontology.owlEditor.service.spreadsheet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.SpreadsheetImportRecord;
import self.research.ontology.owlEditor.repository.SpreadsheetImportRecordRepository;

import java.util.List;

@Component
public class SpreadsheetImportDraftSupport {

    private static final Logger log = LoggerFactory.getLogger(SpreadsheetImportDraftSupport.class);

    public static final String SESSION_PREFIX = "spreadsheet-import-";

    private final SpreadsheetImportRecordRepository records;
    private final MongoTemplate mongo;

    public SpreadsheetImportDraftSupport(SpreadsheetImportRecordRepository records, MongoTemplate mongo) {
        this.records = records;
        this.mongo = mongo;
    }

    public static boolean isImportSession(String sessionId) {
        return sessionId != null && sessionId.startsWith(SESSION_PREFIX);
    }

    public void promoteDraftImports(String projectId, String userId) {
        List<SpreadsheetImportRecord> drafts = records.findByProjectIdAndUserIdAndDraftTrue(projectId, userId);
        for (SpreadsheetImportRecord record : drafts) {
            record.setDraft(false);
            records.save(record);
            mongo.updateMulti(
                Query.query(Criteria.where("projectId").is(projectId)
                    .and("entityIRI").is(SpreadsheetImportService.ENTITY_IRI_PREFIX + record.getId())
                    .and("draft").is(true)),
                new Update().set("draft", false),
                HistoryChange.class);
        }
        if (!drafts.isEmpty()) {
            log.info("[SpreadsheetImport] Published {} draft import(s) for user {} in project {}", drafts.size(), userId, projectId);
        }
    }

    public void discardDraftChanges(String projectId, String userId, String sessionId) {
        if (!isImportSession(sessionId)) {
            return;
        }
        Query pending = pendingQuery(projectId, userId, sessionId);
        List<DraftChange> changes = mongo.find(pending, DraftChange.class);
        if (!changes.isEmpty()) {
            mongo.insert(changes, PARKED_COLLECTION);
            mongo.remove(pending, DraftChange.class);
        }
        log.info("[SpreadsheetImport] Parked {} pending draft changes of undone import session {}", changes.size(), sessionId);
    }

    public void removeDraftChanges(String projectId, String userId, String sessionId) {
        if (isImportSession(sessionId)) {
            mongo.remove(pendingQuery(projectId, userId, sessionId), DraftChange.class);
        }
    }

    public void restoreDraftChanges(String projectId, String userId, String sessionId) {
        if (!isImportSession(sessionId)) {
            return;
        }
        Query parked = pendingQuery(projectId, userId, sessionId);
        List<DraftChange> changes = mongo.find(parked, DraftChange.class, PARKED_COLLECTION);
        if (!changes.isEmpty()) {
            mongo.insert(changes, DraftChange.class);
            mongo.remove(parked, DraftChange.class, PARKED_COLLECTION);
        }
        log.info("[SpreadsheetImport] Restored {} draft changes of redone import session {}", changes.size(), sessionId);
    }

    static final String PARKED_COLLECTION = "spreadsheet_import_parked_drafts";

    private static Query pendingQuery(String projectId, String userId, String sessionId) {
        return Query.query(Criteria.where("projectId").is(projectId)
            .and("userId").is(userId)
            .and("sessionId").is(sessionId)
            .and("applied").is(false));
    }
}
