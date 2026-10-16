package self.research.ontology.owlEditor.service;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class CompositeRollbackActions implements RollbackActionRunner {

    private final PrefixRollbackActions prefixActions;
    private final DocumentRollbackActions documentActions;
    private final StoredFileRollbackActions fileActions;
    private final MergeCheckpointRollbackActions mergeActions;

    public CompositeRollbackActions(PrefixRollbackActions prefixActions, DocumentRollbackActions documentActions,
                                    StoredFileRollbackActions fileActions,
                                    MergeCheckpointRollbackActions mergeActions) {
        this.prefixActions = prefixActions;
        this.documentActions = documentActions;
        this.fileActions = fileActions;
        this.mergeActions = mergeActions;
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
        if (RollbackAction.RESTORE_MERGE_FILE.equals(action.type())) {
            return mergeActions;
        }
        if (RollbackAction.SYNC_STORED_FILE.equals(action.type())) {
            return fileActions;
        }
        return RollbackAction.RESTORE_DOCUMENT.equals(action.type()) ? documentActions : prefixActions;
    }
}
