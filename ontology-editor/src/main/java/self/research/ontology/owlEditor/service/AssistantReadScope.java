package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.document.AssistantSessionDocument;

final class AssistantReadScope implements AutoCloseable {

    private final String previousUserId;
    private final boolean previousWantsDraft;

    private AssistantReadScope(StorageManager.ContentScope scope) {
        previousUserId = SparqlQueryContext.getUserId();
        previousWantsDraft = SparqlQueryContext.wantsDraft();
        SparqlQueryContext.setUserId(scope.userId());
        SparqlQueryContext.setWantsDraft(scope.draft());
    }

    static StorageManager.ContentScope of(AssistantSessionDocument session) {
        return session.isDraft() ? new StorageManager.ContentScope(true, session.getDraftUserId())
                                 : StorageManager.ContentScope.publicScope();
    }

    static AssistantReadScope enter(StorageManager.ContentScope scope) {
        return new AssistantReadScope(scope);
    }

    @Override
    public void close() {
        SparqlQueryContext.setUserId(previousUserId);
        SparqlQueryContext.setWantsDraft(previousWantsDraft);
    }
}
