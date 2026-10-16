package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Slf4j
@Component
public class StoredFileRollbackActions implements RollbackActionRunner {

    private final StorageManager storageManager;
    private final ProjectMetadataService metadataService;
    private final OntologyMergeService mergeService;
    private final ProjectImportService importService;

    @Autowired
    public StoredFileRollbackActions(StorageManager storageManager, ProjectMetadataService metadataService,
                                     @Lazy OntologyMergeService mergeService, @Lazy ProjectImportService importService) {
        this.storageManager = storageManager;
        this.metadataService = metadataService;
        this.mergeService = mergeService;
        this.importService = importService;
    }

    @Override
    public void run(String projectId, RollbackAction action, boolean draft, String draftOwner) {
        if (draft) {
            return;
        }
        try {
            Path exported = storageManager.exportOntology(projectId, "rdfxml");
            Path current = storageManager.findCurrentOntology(projectId).orElse(null);
            if (current == null || !Files.exists(exported)) {
                return;
            }
            Files.copy(exported, current, StandardCopyOption.REPLACE_EXISTING);
            metadataService.readStatus(projectId).map(status -> status.filename())
                    .filter(name -> name != null && !name.isBlank())
                    .ifPresent(name -> mergeService.updateGridFsFile(projectId, name, current));
        } catch (IOException | RuntimeException e) {
            log.warn("[UNDO] Could not bring the stored file of project {} in line with the graph: {}", projectId, e.getMessage());
        }
    }

    @Override
    public boolean holds(String projectId, RollbackAction action, boolean draft, String draftOwner) {
        return draft || !importService.isImportActiveOrQueued(projectId);
    }
}
