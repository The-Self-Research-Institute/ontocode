package self.research.ontology.owlEditor.service;

public interface RollbackActionRunner {

    void run(String projectId, RollbackAction action, boolean draft, String draftOwner);

    boolean holds(String projectId, RollbackAction action, boolean draft, String draftOwner);
}
