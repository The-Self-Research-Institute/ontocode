package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.model.HistoryChange;

public final class PrEntries {

    public static final String IRI_PREFIX = "urn:ontocode:pr:";
    public static final String MERGED_PR_PART = "Part of a merged pull request. Revert the pull request instead";
    public static final String PUBLICATION_RECORD = "This records a pull request being merged. Revert the pull request instead";

    private PrEntries() {
    }

    public static boolean isPublicationRecord(HistoryChange change) {
        return RollbackWorkSupport.isPublicationEntry(change);
    }

    public static String iriOf(String prId) {
        return IRI_PREFIX + prId;
    }

    public static boolean isMergedPrEntry(HistoryChange change) {
        return RollbackWorkSupport.isUndoablePublication(change) && prIdOf(change.getEntityIRI()) != null;
    }

    public static String prIdOf(String entityIri) {
        return entityIri != null && entityIri.startsWith(IRI_PREFIX) ? entityIri.substring(IRI_PREFIX.length()) : null;
    }
}
