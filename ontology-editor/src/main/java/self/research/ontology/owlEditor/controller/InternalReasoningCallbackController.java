package self.research.ontology.owlEditor.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.service.ReasoningJobRelayService;

import java.util.Map;

@RestController
@RequestMapping("/internal/reasoning")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ontocode.reasoner-worker.enabled", havingValue = "true")
public class InternalReasoningCallbackController {

    private final ReasoningJobRelayService relayService;

    @PostMapping("/job-events")
    public ResponseEntity<Void> jobEvent(@RequestBody Map<String, Object> payload) {
        relayService.applyWorkerEvent(payload);
        return ResponseEntity.ok().build();
    }
}
