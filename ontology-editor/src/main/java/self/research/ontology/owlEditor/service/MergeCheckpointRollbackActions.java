package self.research.ontology.owlEditor.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;

import java.io.IOException;
import java.nio.file.Path;

@Component
public class MergeCheckpointRollbackActions implements RollbackActionRunner {

    private static final int LOOKBACK = 50;

    private final StorageManager storageManager;
    private final ProjectMetadataService metadataService;
    private final OntologyMergeService mergeService;
    private final ProjectImportService importService;
    private final HistoryChangeRepository history;

    @Autowired
    public MergeCheckpointRollbackActions(StorageManager storageManager, ProjectMetadataService metadataService,
                                          @Lazy OntologyMergeService mergeService, @Lazy ProjectImportService importService,
                                          HistoryChangeRepository history) {
        this.storageManager = storageManager;
        this.metadataService = metadataService;
        this.mergeService = mergeService;
        this.importService = importService;
        this.history = history;
    }

    @Override
    public void run(String projectId, RollbackAction action, boolean draft, String draftOwner) {
        Path current = storageManager.findCurrentOntology(projectId)
                .orElseThrow(() -> new IllegalStateException("The project file could not be found"));
        try {
            new MergeCheckpointStore(storageManager).restoreTo(projectId, action.document(), undo(action), current);
        } catch (IOException e) {
            throw new IllegalStateException("The saved copy of the file could not be restored: " + e.getMessage(), e);
        }
        metadataService.readStatus(projectId).map(status -> status.filename())
                .filter(name -> name != null && !name.isBlank())
                .ifPresent(name -> mergeService.updateGridFsFile(projectId, name, current));
        importService.submitImport(projectId, current);
    }

    @Override
    public boolean holds(String projectId, RollbackAction action, boolean draft, String draftOwner) {
        if (draft || action.document() == null || importService.isImportActiveOrQueued(projectId)) {
            return false;
        }
        if (!new MergeCheckpointStore(storageManager).exists(projectId, action.document(), undo(action))) {
            return false;
        }
        return nothingNewerThan(projectId, action.documentId());
    }

    private boolean nothingNewerThan(String projectId, String mergeEntryId) {
        for (HistoryChange entry : history.findByProjectId(projectId,
                PageRequest.of(0, LOOKBACK, Sort.by(Sort.Direction.DESC, "timestamp")))) {
            if (mergeEntryId != null && mergeEntryId.equals(entry.getId())) {
                return true;
            }
            if (!RollbackWorkSupport.isRollbackEntry(entry) && !entry.isReverted()
                    && !"projectImported".equals(entry.getOperationType())) {
                return false;
            }
        }
        return false;
    }

    private static boolean undo(RollbackAction action) {
        return RollbackAction.UNDO_SIDE.equals(action.store());
    }
}
