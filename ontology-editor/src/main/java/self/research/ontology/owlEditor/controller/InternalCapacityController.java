package self.research.ontology.owlEditor.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.common.admission.CapacityReport;
import self.research.ontology.common.admission.ReservationLedger;
import self.research.ontology.owlEditor.service.ReasoningTaskRunner;

@RestController
@RequestMapping("/internal/capacity")
public class InternalCapacityController {

    private final ReservationLedger ledger;
    private ReasoningTaskRunner taskRunner;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTaskRunner(ReasoningTaskRunner taskRunner) {
        this.taskRunner = taskRunner;
    }

    public InternalCapacityController(ReservationLedger ledger) {
        this.ledger = ledger;
    }

    @GetMapping
    public ResponseEntity<?> capacity(@RequestParam(required = false) String format) {
        CapacityReport report = ledger.capacityReport();
        if ("text".equalsIgnoreCase(format)) {
            String table = report.renderTable();
            if (taskRunner != null) {
                table = table + System.lineSeparator() + "uncooperative reasoning tasks: " + taskRunner.uncooperativeTasks();
            }
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(table);
        }
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>(report.toMap());
        if (taskRunner != null) {
            body.put("uncooperativeReasoningTasks", taskRunner.uncooperativeTasks());
        }
        return ResponseEntity.ok(body);
    }
}
