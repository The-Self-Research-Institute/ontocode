package self.research.ontology.owlEditor.service;

public record RollbackAction(String type, String prefix, String namespace, String oldPrefix, String expectedNamespace,
                             boolean expectAbsent, String store, String documentId, String document,
                             String expectedDocument) {

    public static final String SET_PREFIX = "prefix.set";
    public static final String DELETE_PREFIX = "prefix.delete";
    public static final String REFRESH_ONTOLOGY = "ontology.refresh";
    public static final String RESTORE_DOCUMENT = "document.restore";

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
