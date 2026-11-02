package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.common.ReasoningFriendlyErrors;
import self.research.ontology.common.admission.RefusalReason;
import self.research.ontology.owlEditor.service.BusyException;
import self.research.ontology.owlEditor.service.ExplainInferenceLimiter;
import self.research.ontology.owlEditor.service.ExplainInferenceRequest;
import self.research.ontology.owlEditor.service.JobAccess;
import self.research.ontology.owlEditor.service.ReasonerWorkerClient;
import self.research.ontology.owlEditor.service.SparqlQueryContext;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/ontology")
@CrossOrigin(originPatterns = "*")
public class ExplainInferenceController {

    public static final List<String> SUPPORTED_AXIOM_TYPES = List.of(
            "ClassAssertion", "SubClassOf", "ObjectPropertyAssertion", "DataPropertyAssertion",
            "ObjectPropertyDomain", "ObjectPropertyRange", "SubObjectPropertyOf", "InverseObjectProperties",
            "EquivalentClasses", "SameIndividual");

    static final String WORKER_LOST_MESSAGE =
            "The reasoning service no longer has this explanation (it may have restarted). Please ask again.";

    static final String UNAVAILABLE_MESSAGE =
            "Explanations need the reasoning service, which is not running in this setup.";

    @Autowired
    private JobAccess jobAccess;

    @Autowired
    private ExplainInferenceLimiter limiter;

    @Autowired(required = false)
    private ReasonerWorkerClient reasonerWorkerClient;

    @Value("${ontocode.reasoner-worker.enabled:false}")
    private boolean reasonerWorkerEnabled;

    @PostMapping("/{projectId}/explain-inference")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable String projectId,
                                                       @RequestBody ExplainInferenceRequest body,
                                                       HttpServletRequest request) {
        Optional<JobAccess.Caller> caller = jobAccess.caller(request);
        if (caller.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("success", false, "error", "Authentication required"));
        }
        if (!jobAccess.hasProject(caller.get(), projectId)) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Project not found"));
        }
        String problem = validate(body);
        if (problem != null) {
            Map<String, Object> refusal = new LinkedHashMap<>();
            refusal.put("success", false);
            if (!isSupported(body)) {
                refusal.put("explainStatus", "UNSUPPORTED");
                refusal.put("supportedAxiomTypes", SUPPORTED_AXIOM_TYPES);
            }
            refusal.put("error", problem);
            return ResponseEntity.badRequest().body(refusal);
        }
        if (!reasonerWorkerEnabled || reasonerWorkerClient == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "success", false, "explainStatus", "UNAVAILABLE", "error", UNAVAILABLE_MESSAGE));
        }

        String owner = caller.get().userId();
        limiter.requireRoom(owner);

        String contextUser = SparqlQueryContext.getUserId();
        String draftUserId = SparqlQueryContext.wantsDraft() && contextUser != null && !contextUser.isBlank()
                ? contextUser : null;
        Map<String, Object> worker = reasonerWorkerClient.submitExplainInference(
                projectId, body, reasonerFor(body), draftUserId);
        if (Boolean.TRUE.equals(worker.get("rateLimited"))) {
            throw new BusyException(RefusalReason.QUEUE_FULL, String.valueOf(worker.get("error")), 10_000);
        }
        if (Boolean.FALSE.equals(worker.get("success")) || worker.get("jobId") == null) {
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", ReasoningFriendlyErrors.forUser(worker.get("error") != null ? worker.get("error").toString() : null)));
        }
        String jobId = worker.get("jobId").toString();
        jobAccess.register(request, jobId, projectId);
        limiter.started(owner, jobId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("async", true);
        response.put("jobId", jobId);
        response.put("status", worker.getOrDefault("status", "QUEUED"));
        response.put("queuePosition", worker.getOrDefault("queuePosition", 1));
        response.put("estimatedWaitTimeMs", worker.getOrDefault("estimatedWaitTimeMs", 0));
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/{projectId}/explain-inference/{jobId}")
    public ResponseEntity<Map<String, Object>> poll(@PathVariable String projectId, @PathVariable String jobId,
                                                    HttpServletRequest request) {
        if (!reasonerWorkerEnabled || reasonerWorkerClient == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "success", false, "explainStatus", "UNAVAILABLE", "error", UNAVAILABLE_MESSAGE));
        }
        Optional<JobAccess.Caller> caller = jobAccess.caller(request);
        if (caller.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("success", false, "error", "Authentication required"));
        }
        if (jobAccess.isRegistered(jobId) && !jobAccess.check(caller.get(), projectId, jobId, null).allowed()) {
            return notFound();
        }
        Map<String, Object> job = reasonerWorkerClient.getJob(jobId);
        if (job == null) {
            return notFound();
        }
        if (job.get("jobId") == null) {
            if (!jobAccess.isRegistered(jobId)) {
                return notFound();
            }
            limiter.finished(caller.get().userId(), jobId);
            Map<String, Object> lost = new LinkedHashMap<>();
            lost.put("success", false);
            lost.put("jobId", jobId);
            lost.put("projectId", projectId);
            lost.put("status", "FAILED");
            lost.put("error", WORKER_LOST_MESSAGE);
            return ResponseEntity.ok(lost);
        }
        if (!ReasonerWorkerClient.EXPLAIN_INFERENCE_JOB_TYPE.equals(job.get("jobType"))) {
            return notFound();
        }
        Object recordProject = job.get("projectId");
        if (!jobAccess.check(caller.get(), projectId, jobId, recordProject != null ? recordProject.toString() : null).allowed()) {
            return notFound();
        }
        String status = String.valueOf(job.get("status")).toUpperCase(Locale.ROOT);
        if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
            limiter.finished(caller.get().userId(), jobId);
        }
        Map<String, Object> response = new HashMap<>(job);
        response.put("projectId", projectId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{projectId}/explain-inference/{jobId}")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String projectId, @PathVariable String jobId,
                                                      HttpServletRequest request) {
        Optional<JobAccess.Caller> caller = jobAccess.caller(request);
        if (caller.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("success", false, "error", "Authentication required"));
        }
        if (!reasonerWorkerEnabled || reasonerWorkerClient == null) {
            return ResponseEntity.ok(Map.of("success", true, "cancelled", false));
        }
        if (!jobAccess.check(caller.get(), projectId, jobId, null).allowed()) {
            return notFound();
        }
        Map<String, Object> result = reasonerWorkerClient.cancelJob(jobId);
        limiter.finished(caller.get().userId(), jobId);
        return ResponseEntity.ok(result);
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(404).body(Map.of("success", false, "error", "Explanation not found"));
    }

    private static String reasonerFor(ExplainInferenceRequest body) {
        return body.reasoner() != null && "ELK".equalsIgnoreCase(body.reasoner().trim()) ? "ELK" : "HERMIT";
    }

    private static boolean isSupported(ExplainInferenceRequest body) {
        return body != null && body.axiomType() != null && SUPPORTED_AXIOM_TYPES.contains(body.axiomType().trim());
    }

    static String validate(ExplainInferenceRequest body) {
        if (body == null || body.axiomType() == null || body.axiomType().isBlank()) {
            return "Say which kind of fact to explain (axiomType). Supported kinds: "
                    + String.join(", ", SUPPORTED_AXIOM_TYPES) + ".";
        }
        String type = body.axiomType().trim();
        if (!SUPPORTED_AXIOM_TYPES.contains(type)) {
            return "Explaining a \"" + type + "\" fact is not supported yet. Supported kinds: "
                    + String.join(", ", SUPPORTED_AXIOM_TYPES) + ".";
        }
        boolean union = body.objectIris() != null && !body.objectIris().isEmpty();
        if (union) {
            if (!"ClassAssertion".equals(type) && !"SubClassOf".equals(type)) {
                return "A union or intersection of classes can only be used with ClassAssertion or SubClassOf.";
            }
            if (body.objectIris().size() < 2 || body.objectIris().stream().anyMatch(iri -> iri == null || iri.isBlank())) {
                return "A union or intersection needs at least two class IRIs in objectIris.";
            }
            String operator = body.objectOperator() == null ? "" : body.objectOperator().trim().toUpperCase(Locale.ROOT);
            if (!"UNION".equals(operator) && !"INTERSECTION".equals(operator)) {
                return "objectOperator must be UNION or INTERSECTION.";
            }
        }
        if (blank(body.subjectIri())) {
            return "subjectIri is required for " + type + ".";
        }
        switch (type) {
            case "ObjectPropertyAssertion" -> {
                if (blank(body.predicateIri()) || blank(body.objectIri())) {
                    return "predicateIri and objectIri are required for ObjectPropertyAssertion.";
                }
            }
            case "DataPropertyAssertion" -> {
                if (blank(body.predicateIri()) || body.objectLiteral() == null) {
                    return "predicateIri and objectLiteral are required for DataPropertyAssertion.";
                }
            }
            default -> {
                if (!union && blank(body.objectIri())) {
                    return "objectIri is required for " + type + ".";
                }
            }
        }
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
