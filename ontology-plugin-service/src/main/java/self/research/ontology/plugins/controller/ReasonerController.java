package self.research.ontology.plugins.controller;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.client.RestTemplate;
import self.research.ontology.common.ReasoningFriendlyErrors;
import self.research.ontology.plugins.service.ReasonerWorkerClient;

import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController("pluginReasonerController")
@RequestMapping("/api/reasoner")
@CrossOrigin(originPatterns = "*")
public class ReasonerController {

    private static final Logger log = LoggerFactory.getLogger(ReasonerController.class);

    @Autowired(required = false)
    private ReasonerWorkerClient reasonerWorkerClient;

    @Value("${ontocode.reasoner-worker.enabled:false}")
    private boolean reasonerWorkerEnabled;

    @Value("${ontology.editor.url:http://owl-editor:8083}")
    private String editorServiceUrl;

    @Autowired
    @Qualifier("ontologyGridFsTemplate")
    private GridFsTemplate gridfs;

    private final RestTemplate restTemplate = buildRestTemplate();

    private static RestTemplate buildRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
            new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(30_000);
        return new RestTemplate(f);
    }

    // ─── Worker proxy helpers ────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> workerUnavailable() {
        return ResponseEntity.status(503).body(Map.of(
            "success", false,
            "error", "Reasoning worker is not available. Contact your administrator."
        ));
    }

    private ResponseEntity<Map<String, Object>> submitToWorker(
            String jobType, String projectId, String reasonerType) {
        if (!reasonerWorkerEnabled || reasonerWorkerClient == null) {
            return workerUnavailable();
        }
        try {
            Map<String, Object> worker = reasonerWorkerClient.submit(jobType, projectId, reasonerType);
            if (Boolean.FALSE.equals(worker.get("success"))) {
                return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", ReasoningFriendlyErrors.forUser(String.valueOf(worker.get("error")))));
            }
            String jobId = String.valueOf(worker.get("jobId"));
            return ResponseEntity.accepted().body(Map.of(
                "async", true,
                "taskId", jobId,
                "jobId", jobId,
                "status", worker.getOrDefault("status", "QUEUED"),
                "pollUrl", "/api/dl-query/jobs/" + jobId));
        } catch (Exception e) {
            log.error("Error submitting {} job for project {}", jobType, projectId, e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", ReasoningFriendlyErrors.forUser(e.getMessage())));
        }
    }

    private ResponseEntity<Map<String, Object>> mapWorkerJobStatus(String taskId, Map<String, Object> remote) {
        Map<String, Object> response = new HashMap<>(remote);
        response.put("taskId", taskId);
        String status = String.valueOf(remote.getOrDefault("status", "QUEUED"));
        if ("COMPLETED".equals(status)) {
            response.put("status", "COMPLETED");
            response.put("success", true);
        } else if ("FAILED".equals(status)) {
            response.put("status", "FAILED");
            response.put("success", false);
            response.put("error", ReasoningFriendlyErrors.forUser(String.valueOf(remote.get("error"))));
        } else {
            response.put("status", "RUNNING");
        }
        return ResponseEntity.ok(response);
    }

    // ─── Reasoning endpoints ─────────────────────────────────────────────────

    @PostMapping("/{projectId}/consistency")
    public ResponseEntity<Map<String, Object>> checkConsistency(
            @PathVariable String projectId,
            @RequestBody Map<String, String> request) {
        log.info("Consistency check for project: {}", projectId);
        return submitToWorker("REASONER_CONSISTENCY", projectId,
                request.getOrDefault("reasonerType", "HERMIT"));
    }

    @PostMapping("/{projectId}/classify")
    public ResponseEntity<Map<String, Object>> classify(
            @PathVariable String projectId,
            @RequestBody Map<String, String> request) {
        log.info("Classify for project: {}", projectId);
        return submitToWorker("REASONER_CLASSIFY", projectId,
                request.getOrDefault("reasonerType", "HERMIT"));
    }

    @GetMapping("/{projectId}/classify/status/{taskId}")
    public ResponseEntity<Map<String, Object>> classifyStatus(
            @PathVariable String projectId,
            @PathVariable String taskId) {
        if (!reasonerWorkerEnabled || reasonerWorkerClient == null) {
            return workerUnavailable();
        }
        Map<String, Object> remote = reasonerWorkerClient.getJob(taskId);
        if (remote == null || remote.get("jobId") == null) {
            return ResponseEntity.status(404).body(Map.of(
                "success", false,
                "error", "Task not found: " + taskId));
        }
        return mapWorkerJobStatus(taskId, remote);
    }

    @PostMapping("/{projectId}/realize")
    public ResponseEntity<Map<String, Object>> realize(
            @PathVariable String projectId,
            @RequestBody Map<String, String> request) {
        log.info("Realize for project: {}", projectId);
        return submitToWorker("REASONER_REALIZE", projectId,
                request.getOrDefault("reasonerType", "HERMIT"));
    }

    @GetMapping("/{projectId}/inferred-axioms")
    public ResponseEntity<Map<String, Object>> getInferredAxioms(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType) {
        log.info("Inferred axioms for project: {}", projectId);
        return submitToWorker("REASONER_RUN", projectId, reasonerType);
    }

    @PostMapping("/{projectId}/explain-inconsistency")
    public ResponseEntity<Map<String, Object>> explainInconsistency(
            @PathVariable String projectId,
            @RequestBody Map<String, String> request) {
        // REASONER_EXPLAIN is not yet a worker job type.
        return ResponseEntity.status(501).body(Map.of(
            "success", false,
            "error", "Explain inconsistency is not yet supported via the reasoning worker."));
    }

    @GetMapping("/{projectId}/stats")
    public ResponseEntity<Map<String, Object>> getReasonerStats(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType) {
        // Stats are per-session; poll /api/dl-query/jobs/{jobId} for job progress instead.
        return ResponseEntity.status(501).body(Map.of(
            "success", false,
            "error", "Reasoner stats are not available for worker-based reasoning."));
    }

    @PostMapping("/{projectId}/stop")
    public ResponseEntity<Map<String, Object>> stopReasoner(
            @PathVariable String projectId,
            @RequestParam(required = false) String reasonerType) {
        // Worker jobs are self-managed; nothing to stop locally.
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "No local reasoner running.",
            "projectId", projectId));
    }

    @PostMapping("/clear-cache")
    public ResponseEntity<Map<String, Object>> clearCache() {
        // No local OWL cache — nothing to clear.
        return ResponseEntity.ok(Map.of("success", true, "message", "Cache cleared."));
    }

    // ─── Diagnostic endpoint ─────────────────────────────────────────────────

    @GetMapping("/diagnose/{projectId}")
    public ResponseEntity<Map<String, Object>> diagnoseFileStorage(@PathVariable String projectId) {
        try {
            Map<String, Object> diagnosis = new LinkedHashMap<>();
            diagnosis.put("projectId", projectId);
            diagnosis.put("timestamp", new java.util.Date());
            diagnosis.put("editorServiceUrl", editorServiceUrl);
            diagnosis.put("workerEnabled", reasonerWorkerEnabled);

            // Check editor service reachability (forward JWT if present)
            String encoded = java.net.URLEncoder.encode(projectId, StandardCharsets.UTF_8).replace("+", "%20");
            String base = editorServiceUrl.endsWith("/")
                    ? editorServiceUrl.substring(0, editorServiceUrl.length() - 1) : editorServiceUrl;
            String url = base + "/api/ontology-file/" + encoded;
            Map<String, Object> editorCheck = new LinkedHashMap<>();
            editorCheck.put("url", url);
            try {
                HttpHeaders headers = new HttpHeaders();
                try {
                    ServletRequestAttributes attrs =
                            (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
                    String auth = attrs.getRequest().getHeader("Authorization");
                    if (auth != null && auth.startsWith("Bearer ")) {
                        headers.set("Authorization", auth);
                    }
                } catch (Exception ignored) { }
                RequestEntity<Void> req = new RequestEntity<>(headers, HttpMethod.GET,
                        java.net.URI.create(url));
                ResponseEntity<byte[]> resp = restTemplate.exchange(req, byte[].class);
                editorCheck.put("httpStatus", resp.getStatusCode().value());
                byte[] body = resp.getBody();
                editorCheck.put("bytes", body != null ? body.length : 0);
                editorCheck.put("available", resp.getStatusCode() == HttpStatus.OK
                        && body != null && body.length > 0);
            } catch (Exception e) {
                editorCheck.put("available", false);
                editorCheck.put("error", e.getMessage());
            }
            diagnosis.put("editorService", editorCheck);

            // Check GridFS
            GridFSFile fileByMeta = gridfs.findOne(
                    new Query(Criteria.where("metadata.projectId").is(projectId)));
            GridFSFile fileByName = fileByMeta == null
                    ? gridfs.findOne(new Query(Criteria.where("filename").is(projectId + ".owl")))
                    : null;
            GridFSFile found = fileByMeta != null ? fileByMeta : fileByName;
            if (found != null) {
                diagnosis.put("foundInGridFS", true);
                diagnosis.put("searchMethod", fileByMeta != null ? "metadata.projectId" : "filename");
                diagnosis.put("gridfsFileId", found.getObjectId().toString());
                diagnosis.put("filename", found.getFilename());
                diagnosis.put("length", found.getLength());
                if (found.getMetadata() != null) {
                    diagnosis.put("metadata", found.getMetadata());
                }
            } else {
                diagnosis.put("foundInGridFS", false);
                diagnosis.put("note", "File not found by metadata.projectId or filename. "
                        + "The file may be retrievable via file_metadata.gridfsId lookup.");
            }

            // Recent GridFS files (last 10)
            List<Map<String, Object>> recentFiles = new ArrayList<>();
            gridfs.find(new Query().limit(10))
                .sort(new org.bson.Document("uploadDate", -1))
                .forEach(file -> recentFiles.add(Map.of(
                    "filename", file.getFilename(),
                    "uploadDate", String.valueOf(file.getUploadDate()),
                    "fileId", file.getObjectId().toString())));
            diagnosis.put("recentGridFSFiles", recentFiles);

            return ResponseEntity.ok(diagnosis);
        } catch (Exception e) {
            log.error("Error diagnosing file storage for project: {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage(),
                "projectId", projectId));
        }
    }
}
