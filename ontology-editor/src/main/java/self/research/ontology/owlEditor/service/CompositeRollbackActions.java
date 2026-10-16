package self.research.ontology.owlEditor.service;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class CompositeRollbackActions implements RollbackActionRunner {

    private final PrefixRollbackActions prefixActions;
    private final DocumentRollbackActions documentActions;

    public CompositeRollbackActions(PrefixRollbackActions prefixActions, DocumentRollbackActions documentActions) {
        this.prefixActions = prefixActions;
        this.documentActions = documentActions;
    }

    @Override
    public void run(String projectId, RollbackAction action, boolean draft, String draftOwner) {
        runnerFor(action).run(projectId, action, draft, draftOwner);
    }

    @Override
    public boolean holds(String projectId, RollbackAction action, boolean draft, String draftOwner) {
        return runnerFor(action).holds(projectId, action, draft, draftOwner);
    }

    private RollbackActionRunner runnerFor(RollbackAction action) {
        return RollbackAction.RESTORE_DOCUMENT.equals(action.type()) ? documentActions : prefixActions;
    }
}
