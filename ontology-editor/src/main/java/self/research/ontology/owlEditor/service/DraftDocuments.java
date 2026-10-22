package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.service.spreadsheet.SpreadsheetImportDraftSupport;

import java.util.List;

@Component
public class DraftDocuments {

    private static final Logger log = LoggerFactory.getLogger(DraftDocuments.class);

    private final List<DraftDocumentSupport> supports;
    private SpreadsheetImportDraftSupport spreadsheetImports;

    public DraftDocuments(List<DraftDocumentSupport> supports) {
        this.supports = supports;
    }

    public static DraftDocuments none() {
        return new DraftDocuments(List.of());
    }

    @Autowired(required = false)
    void setSpreadsheetImports(SpreadsheetImportDraftSupport spreadsheetImports) {
        this.spreadsheetImports = spreadsheetImports;
    }

    public void promote(String projectId, String userId) {
        if (spreadsheetImports != null) {
            try {
                spreadsheetImports.promoteDraftImports(projectId, userId);
            } catch (Exception e) {
                log.warn("[DRAFT] Could not mark spreadsheet imports as published for project {} user {}: {}",
                        projectId, userId, e.getMessage());
            }
        }
        for (DraftDocumentSupport support : supports) {
            try {
                support.promote(projectId, userId);
            } catch (Exception e) {
                log.warn("[DRAFT] Could not publish draft documents of {} for project {} user {}: {}",
                        support.getClass().getSimpleName(), projectId, userId, e.getMessage());
            }
        }
    }

    public void discard(String projectId, String userId) {
        for (DraftDocumentSupport support : supports) {
            try {
                support.discard(projectId, userId);
            } catch (Exception e) {
                log.warn("[DRAFT] Could not discard draft documents of {} for project {} user {}: {}",
                        support.getClass().getSimpleName(), projectId, userId, e.getMessage());
            }
        }
    }
}
