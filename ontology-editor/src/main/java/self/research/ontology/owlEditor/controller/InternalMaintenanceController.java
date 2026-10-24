package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.DraftHistoryOrphanCleaner;

import java.util.Map;

@RestController
@RequestMapping("/internal/maintenance")
public class InternalMaintenanceController {

    private final DraftHistoryOrphanCleaner cleaner;

    public InternalMaintenanceController(DraftHistoryOrphanCleaner cleaner) {
        this.cleaner = cleaner;
    }

    @PostMapping("/draft-history/cleanup")
    public ResponseEntity<Map<String, Object>> cleanDraftHistory(@RequestParam(defaultValue = "true") boolean dryRun) {
        return ResponseEntity.ok(cleaner.run(dryRun));
    }
}
