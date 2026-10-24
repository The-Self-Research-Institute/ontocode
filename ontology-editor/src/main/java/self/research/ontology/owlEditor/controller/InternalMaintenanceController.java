package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.DraftHistoryOrphanCleaner;
import self.research.ontology.owlEditor.service.PrHistoryBackfill;

import java.util.Map;

@RestController
@RequestMapping("/internal/maintenance")
public class InternalMaintenanceController {

    private final DraftHistoryOrphanCleaner cleaner;
    private final PrHistoryBackfill backfill;

    public InternalMaintenanceController(DraftHistoryOrphanCleaner cleaner, PrHistoryBackfill backfill) {
        this.cleaner = cleaner;
        this.backfill = backfill;
    }

    @PostMapping("/draft-history/cleanup")
    public ResponseEntity<Map<String, Object>> cleanDraftHistory(@RequestParam(defaultValue = "true") boolean dryRun) {
        return ResponseEntity.ok(cleaner.run(dryRun));
    }

    @PostMapping("/pr-history/backfill")
    public ResponseEntity<Map<String, Object>> backfillPrHistory(@RequestParam(defaultValue = "true") boolean dryRun) {
        return ResponseEntity.ok(backfill.run(dryRun));
    }
}
