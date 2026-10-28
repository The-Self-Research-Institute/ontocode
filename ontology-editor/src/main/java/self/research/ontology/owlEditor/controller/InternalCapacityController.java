package self.research.ontology.owlEditor.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.common.admission.CapacityReport;
import self.research.ontology.common.admission.ReservationLedger;

@RestController
@RequestMapping("/internal/capacity")
public class InternalCapacityController {

    private final ReservationLedger ledger;

    public InternalCapacityController(ReservationLedger ledger) {
        this.ledger = ledger;
    }

    @GetMapping
    public ResponseEntity<?> capacity(@RequestParam(required = false) String format) {
        CapacityReport report = ledger.capacityReport();
        if ("text".equalsIgnoreCase(format)) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(report.renderTable());
        }
        return ResponseEntity.ok(report.toMap());
    }
}
