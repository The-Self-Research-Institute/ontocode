package self.research.ontology.owlEditor.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.semanticweb.owlapi.formats.NTriplesDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import self.research.ontology.owlEditor.service.ImportQueueManager;
import self.research.ontology.owlEditor.service.MainGraphRevisionService;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.owlapi.OwlApiOntologyContext;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalReasoningSupportController {

    private final SparqlDatasetService datasetService;
    private final ImportQueueManager importQueueManager;
    private final MainGraphRevisionService mainGraphRevisionService;
    private final ProjectImportService importService;

    @Autowired(required = false) @Nullable
    private OwlApiOntologyContext owlApiContext;

    @GetMapping("/reasoning/{projectId}/triple-count")
    public ResponseEntity<Map<String, Object>> tripleCount(@PathVariable String projectId) {
        try {
            long count = datasetService.getDatasetSize(projectId);
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
            @RequestParam(required = false) String userId) {
        // This is reasoner-worker's primary path for fetching a project's ontology content.
        // Desktop OWLAPI-first: serialize directly from the already-cached in-memory model, the
        // same way Protege reasons with no external triple store involved at all — only fall
        // back to Fuseki when there's a draft overlay (userId; the in-memory cache isn't
        // draft-aware) or the model genuinely isn't warmed yet (cloud, or desktop before first
        // open). Reading Fuseki unconditionally here previously returned stale or empty data
        // whenever it wasn't already running/synced, which reasoner-worker reported as "ontology
        // not found" (surfaced to users via ReasoningFriendlyErrors as "We could not find this
        // project's ontology").
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
        StreamingResponseBody body = out -> datasetService.exportDatasetToStream(projectId, userId, RDFFormat.TURTLE, out);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/turtle"))
                .body(body);
    }

    @GetMapping(value = "/reasoning/{projectId}/export.nt", produces = "application/n-triples")
    public ResponseEntity<StreamingResponseBody> exportNTriples(
            @PathVariable String projectId,
            @RequestParam(required = false) String userId) {
        // See exportTurtle() above for why the OWLAPI-first path and the fallback sync are here.
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
        StreamingResponseBody body = out -> datasetService.exportDatasetToStream(projectId, userId, RDFFormat.NTRIPLES, out);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/n-triples"))
                .body(body);
    }

    /**
     * Deep-copies the live cached model into an independent manager before serializing — the
     * live model can be mutated in-place by an edit elsewhere while this streams, and OWLAPI's
     * internal collections aren't safe to iterate concurrently with a structural change.
     */
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
