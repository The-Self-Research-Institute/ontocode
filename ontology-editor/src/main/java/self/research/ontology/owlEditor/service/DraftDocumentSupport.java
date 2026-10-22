package self.research.ontology.owlEditor.service;

public interface DraftDocumentSupport {

    void promote(String projectId, String userId);

    void discard(String projectId, String userId);
}
