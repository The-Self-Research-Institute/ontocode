package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.semanticweb.owlapi.formats.NTriplesDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import self.research.ontology.owlEditor.service.ImportQueueManager;
import self.research.ontology.owlEditor.service.MainGraphRevisionService;
import self.research.ontology.owlEditor.service.ProjectAccessService;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.SparqlQueryContext;
import self.research.ontology.owlEditor.service.StorageManager;
import self.research.ontology.owlEditor.service.owlapi.OwlApiOntologyContext;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalReasoningSupportController {

    private final SparqlDatasetService datasetService;
    private final ImportQueueManager importQueueManager;
    private final MainGraphRevisionService mainGraphRevisionService;
    private final ProjectImportService importService;
    private final ProjectAccessService projectAccessService;

    @Autowired(required = false) @Nullable
    private OwlApiOntologyContext owlApiContext;

    @Autowired(required = false) @Nullable
    private StorageManager storageManager;

    private static final String INSTANCE_ID = UUID.randomUUID().toString();

    @Value("${ontocode.internal.token:ontocode-internal}")
    private String internalToken;

    private boolean draftAccessDenied(String projectId, HttpServletRequest request) {
        if (internalToken != null && internalToken.equals(request.getHeader("X-Ontocode-Internal-Token"))) {
            return false;
        }
        return JwtIdentityExtractor.extractEmail(request)
                .map(email -> !projectAccessService.hasProjectAccess(projectId, email))
                .orElse(true);
    }

    @GetMapping("/reasoning/{projectId}/triple-count")
    public ResponseEntity<Map<String, Object>> tripleCount(@PathVariable String projectId) {
        try {
            long count = datasetService.getDatasetSizeOrUnknown(projectId);
            return ResponseEntity.ok(Map.of("projectId", projectId, "tripleCount", count));
        } catch (Exception e) {
            log.error("Triple count failed for {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage() != null ? e.getMessage() : "Failed to read ontology size"
            ));
        }
    }

    @GetMapping(value = "/reasoning/{projectId}/export.ttl", produces = "text/turtle")
    public ResponseEntity<StreamingResponseBody> exportTurtle(
            @PathVariable String projectId,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "false") boolean draft,
            HttpServletRequest request) {

        if (userId != null && !userId.isBlank() && draftAccessDenied(projectId, request)) {
            return ResponseEntity.status(403).build();
        }

        if ((userId == null || userId.isBlank()) && owlApiContext != null && owlApiContext.hasOntology(projectId)) {
            Optional<OWLOntology> snapshot = snapshotOntology(projectId);
            if (snapshot.isPresent()) {
                StreamingResponseBody body = out -> writeOntology(snapshot.get(), new TurtleDocumentFormat(), out);
                return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/turtle")).body(body);
            }
        }
        if (!ensureFusekiReady(projectId)) {
            return ResponseEntity.status(503).build();
        }
        StreamingResponseBody body = out -> {
            SparqlQueryContext.setWantsDraft(draft);
            try {
                datasetService.exportDatasetToStream(projectId, userId, RDFFormat.TURTLE, out);
            } finally {
                SparqlQueryContext.clear();
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/turtle"))
                .body(body);
    }

    @GetMapping(value = "/reasoning/{projectId}/export.nt", produces = "application/n-triples")
    public ResponseEntity<StreamingResponseBody> exportNTriples(
            @PathVariable String projectId,
            @RequestParam(required = false) String userId,
            HttpServletRequest request) {

        if (userId != null && !userId.isBlank() && draftAccessDenied(projectId, request)) {
            return ResponseEntity.status(403).build();
        }

        if ((userId == null || userId.isBlank()) && owlApiContext != null && owlApiContext.hasOntology(projectId)) {
            Optional<OWLOntology> snapshot = snapshotOntology(projectId);
            if (snapshot.isPresent()) {
                StreamingResponseBody body = out -> writeOntology(snapshot.get(), new NTriplesDocumentFormat(), out);
                return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/n-triples")).body(body);
            }
        }
        if (!ensureFusekiReady(projectId)) {
            return ResponseEntity.status(503).build();
        }
        boolean draft = userId != null && !userId.isBlank();
        StreamingResponseBody body = out -> {
            SparqlQueryContext.setUserId(userId);
            SparqlQueryContext.setWantsDraft(draft);
            try {
                datasetService.exportDatasetToStream(projectId, userId, RDFFormat.NTRIPLES, out);
            } finally {
                SparqlQueryContext.clear();
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/n-triples"))
                .body(body);
    }

    @GetMapping(value = "/reasoning/{projectId}/whatif/{whatIfKey}/export.nt", produces = "application/n-triples")
    public ResponseEntity<String> exportWhatIfNTriples(@PathVariable String projectId, @PathVariable String whatIfKey) {
        try {
            String content = datasetService.exportDraftGraphContent(projectId, whatIfKey, RDFFormat.NTRIPLES);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/n-triples")).body(content);
        } catch (Exception e) {
            log.warn("[InternalReasoning] What-if export failed for project {} key {}: {}",
                    projectId, whatIfKey, e.getMessage());
            return ResponseEntity.status(500).build();
        }
    }

    private Optional<OWLOntology> snapshotOntology(String projectId) {
        return owlApiContext.ontology(projectId).map(live -> {
            try {
                return org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager()
                        .copyOntology(live, org.semanticweb.owlapi.model.parameters.OntologyCopy.DEEP);
            } catch (Exception e) {
                log.warn("[InternalReasoning] Failed to snapshot in-memory OWLAPI model for {}: {}", projectId, e.getMessage());
                return null;
            }
        }).filter(java.util.Objects::nonNull);
    }

    private void writeOntology(OWLOntology ontology, org.semanticweb.owlapi.model.OWLDocumentFormat format,
                                java.io.OutputStream out) throws IOException {
        try {
            ontology.getOWLOntologyManager().saveOntology(ontology, format, out);
        } catch (OWLOntologyStorageException e) {
            throw new IOException("Failed to serialize ontology: " + e.getMessage(), e);
        }
    }

    private boolean ensureFusekiReady(String projectId) {
        try {
            Map<String, Object> result = importService.syncProjectToFuseki(projectId);
            if (Boolean.FALSE.equals(result.get("synced"))) {
                log.warn("[InternalReasoning] Fuseki sync failed for {} before export: {}", projectId, result.get("error"));
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("[InternalReasoning] Fuseki sync failed for {} before export: {}", projectId, e.getMessage());
            return false;
        }
    }

    @GetMapping("/reasoning/{projectId}/draft-version")
    public ResponseEntity<Map<String, Object>> draftVersion(
            @PathVariable String projectId,
            @RequestParam String userId,
            HttpServletRequest request) {
        if (draftAccessDenied(projectId, request)) {
            return ResponseEntity.status(403).build();
        }
        long draftVersion = storageManager != null ? storageManager.getDraftGraphVersion(projectId, userId) : 0L;
        long writeStamp = datasetService.getDraftWriteStamp(projectId, userId);
        return ResponseEntity.ok(Map.of(
                "projectId", projectId,
                "draftVersion", draftVersion,
                "writeStamp", writeStamp,
                "version", INSTANCE_ID + ":" + draftVersion + "." + writeStamp));
    }

    @GetMapping("/reasoning/{projectId}/revision")
    public ResponseEntity<Map<String, Object>> revision(@PathVariable String projectId) {
        try {
            long rev = mainGraphRevisionService.getRevision(projectId);
            return ResponseEntity.ok(Map.of("projectId", projectId, "revision", rev));
        } catch (Exception e) {
            log.error("Revision check failed for {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "Failed to read revision"));
        }
    }

    @GetMapping("/import-queue/stats")
    public ResponseEntity<Map<String, Object>> importStats() {
        var stats = importQueueManager.getQueueStats();
        return ResponseEntity.ok(Map.of(
                "activeImports", stats.getActiveImports(),
                "queuedImports", stats.getQueuedImports()
        ));
    }
}
