package self.research.ontology.owlEditor.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.util.AtomicFiles;

@Slf4j
@Component
public class InterruptedSaveRecovery {

    private final StorageManager storageManager;

    public InterruptedSaveRecovery(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    @PostConstruct
    void recoverInterruptedSaves() {
        int restored = AtomicFiles.recoverAll(storageManager.projectsRoot());
        if (restored > 0) {
            log.warn("[Storage] Restored {} file(s) left behind by saves that were interrupted", restored);
        }
    }
}
