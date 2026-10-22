package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.model.HistoryChange;

public record RollbackAction(String type, String prefix, String namespace, String oldPrefix, String expectedNamespace,
                             boolean expectAbsent, String store, String documentId, String document,
                             String expectedDocument) {

    public static final String SET_PREFIX = "prefix.set";
    public static final String DELETE_PREFIX = "prefix.delete";
    public static final String REFRESH_ONTOLOGY = "ontology.refresh";
    public static final String RESTORE_DOCUMENT = "document.restore";
    public static final String SYNC_STORED_FILE = "file.sync";
    public static final String RESTORE_MERGE_FILE = "merge.restore";
    public static final String UNDO_SIDE = "undo";
    public static final String REDO_SIDE = "redo";

    public static RollbackAction restoreMergeFile(HistoryChange change, boolean undo) {
        String checkpoint = change.getEntityIRI() == null ? null
                : change.getEntityIRI().substring(change.getEntityIRI().lastIndexOf(':') + 1);
        return new RollbackAction(RESTORE_MERGE_FILE, null, null, null, null, false, undo ? UNDO_SIDE : REDO_SIDE,
                change.getId(), checkpoint, null);
    }

    public static RollbackAction syncStoredFile() {
        return new RollbackAction(SYNC_STORED_FILE, null, null, null, null, false, null, null, null, null);
    }

    public static RollbackAction refreshOntology() {
        return new RollbackAction(REFRESH_ONTOLOGY, null, null, null, null, false, null, null, null, null);
    }

    public static RollbackAction setPrefix(String prefix, String namespace, String oldPrefix, String expectedNamespace,
                                           boolean expectAbsent) {
        return new RollbackAction(SET_PREFIX, prefix, namespace, oldPrefix, expectedNamespace, expectAbsent, null, null,
                null, null);
    }

    public static RollbackAction deletePrefix(String prefix, String expectedNamespace) {
        return new RollbackAction(DELETE_PREFIX, prefix, null, null, expectedNamespace, false, null, null, null, null);
    }

    public static RollbackAction restoreDocument(String store, String documentId, String document, String expectedDocument) {
        return new RollbackAction(RESTORE_DOCUMENT, null, null, null, null, false, store, documentId, document,
                expectedDocument);
    }
}
