package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.model.merge.ConflictResolution;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DraftPublishMergeService {

    private static final Logger log = LoggerFactory.getLogger(DraftPublishMergeService.class);

    private final SparqlDatasetService datasetService;
    private final StorageManager storageManager;
    private final OntologyMergeService mergeService;
    private final DraftSessionRepository sessionRepository;

    public DraftPublishMergeService(SparqlDatasetService datasetService,
                                    StorageManager storageManager,
                                    OntologyMergeService mergeService,
                                    DraftSessionRepository sessionRepository) {
        this.datasetService = datasetService;
        this.storageManager = storageManager;
        this.mergeService = mergeService;
        this.sessionRepository = sessionRepository;
    }

    public String captureBaselineSnapshot(String projectId, String userId) throws IOException {
        String mainGraph = datasetService.getGraphUri(projectId);
        String rdf = datasetService.exportNamedGraph(projectId, mainGraph, RDFFormat.RDFXML);
        String relative = "baselines/" + sanitizeUserId(userId) + ".owl";
        Path path = storageManager.projectDir(projectId).resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, rdf);
        log.info("[DRAFT-MERGE] Captured baseline snapshot for project {} user {} → {}",
                projectId, userId, path);
        return relative;
    }

    public void deleteBaselineSnapshot(String projectId, String userId) {
        sessionRepository.findByProjectIdAndUserId(projectId, userId).ifPresent(session -> {
            String relative = session.getBaselineSnapshotPath();
            if (relative != null && !relative.isBlank()) {
                try {
                    Files.deleteIfExists(storageManager.projectDir(projectId).resolve(relative));
                } catch (IOException e) {
                    log.warn("[DRAFT-MERGE] Could not delete baseline snapshot {}: {}", relative, e.getMessage());
                }
            }
        });
    }

    /**
     * Publish via OWLAPI three-way merge (baseline / ours / theirs) and write result to main graph.
     */
    public void publishWithThreeWayMerge(String projectId,
                                         String userId,
                                         DraftPublishAnalysis analysis,
                                         Map<String, ConflictResolution> resolutions) throws Exception {
        Optional<DraftSession> sessionOpt = sessionRepository.findByProjectIdAndUserId(projectId, userId);
        if (sessionOpt.isEmpty() || sessionOpt.get().getBaselineSnapshotPath() == null) {
            log.warn("[DRAFT-MERGE] No baseline snapshot — falling back to graph union publish");
            datasetService.publishDraftGraphToMain(projectId, userId);
            return;
        }

        Path baselinePath = storageManager.projectDir(projectId)
                .resolve(sessionOpt.get().getBaselineSnapshotPath());
        if (!Files.exists(baselinePath)) {
            log.warn("[DRAFT-MERGE] Baseline file missing — falling back to graph union publish");
            datasetService.publishDraftGraphToMain(projectId, userId);
            return;
        }

        String baselineRdf = Files.readString(baselinePath);
        OWLOntology baseline = mergeService.loadOntologyFromRdf(baselineRdf);

        String mainGraph = datasetService.getGraphUri(projectId);
        String mainRdf = datasetService.exportNamedGraph(projectId, mainGraph, RDFFormat.RDFXML);
        OWLOntology theirs = mergeService.loadOntologyFromRdf(mainRdf);

        OWLOntology ours = buildOursOntology(projectId, userId, baselineRdf);

        Set<String> conflictIris = analysis.getConflicts().stream()
                .map(row -> (String) row.get("entityIRI"))
                .filter(iri -> iri != null && !iri.isBlank())
                .collect(Collectors.toSet());

        OWLOntology merged = mergeService.mergeDraftPublishThreeWay(
                baseline, ours, theirs, conflictIris, resolutions);

        String mergedRdf = mergeService.saveOntologyToRdfXml(merged);
        datasetService.replaceMainGraphFromRdf(projectId, mergedRdf, RDFFormat.RDFXML);
        datasetService.clearDraftGraph(projectId, userId);

        log.info("[DRAFT-MERGE] Published project {} user {} via three-way merge ({} conflict IRIs)",
                projectId, userId, conflictIris.size());
    }

    /**
     * Enrich publish-preview conflicts with axiom summaries from three-way comparison.
     */
    public List<Map<String, Object>> enrichConflictsWithAxiomDetail(String projectId,
                                                                    String userId,
                                                                    List<Map<String, Object>> conflicts) {
        if (conflicts == null || conflicts.isEmpty()) {
            return conflicts;
        }
        try {
            String mainRdf = datasetService.exportNamedGraph(
                    projectId, datasetService.getGraphUri(projectId), RDFFormat.RDFXML);
            OWLOntology theirs = mergeService.loadOntologyFromRdf(mainRdf);

            // Build ours ontology: use baseline+draft when snapshot exists, fall back to draft graph only.
            Optional<DraftSession> sessionOpt = sessionRepository.findByProjectIdAndUserId(projectId, userId);
            String baselineSnapshotPath = sessionOpt.map(DraftSession::getBaselineSnapshotPath).orElse(null);
            Path baselinePath = baselineSnapshotPath != null
                    ? storageManager.projectDir(projectId).resolve(baselineSnapshotPath)
                    : null;
            OWLOntology ours;
            if (baselinePath != null && Files.exists(baselinePath)) {
                String baselineRdf = Files.readString(baselinePath);
                ours = buildOursOntology(projectId, userId, baselineRdf);
            } else {
                // No baseline snapshot (copy-on-switch session): load draft graph directly.
                String draftGraph = datasetService.getDraftGraphUri(projectId, userId);
                String draftRdf = datasetService.exportNamedGraph(projectId, draftGraph, RDFFormat.RDFXML);
                ours = mergeService.loadOntologyFromRdf(draftRdf != null ? draftRdf : mainRdf);
            }

            List<Map<String, Object>> enriched = new java.util.ArrayList<>();
            for (Map<String, Object> row : conflicts) {
                Map<String, Object> copy = new LinkedHashMap<>(row);
                String iriStr = (String) row.get("entityIRI");
                if (iriStr != null) {
                    IRI iri = IRI.create(iriStr);
                    copy.put("yourAxioms", summarizeAxioms(ours, iri));
                    copy.put("mainAxioms", summarizeAxioms(theirs, iri));
                }
                enriched.add(copy);
            }
            return enriched;
        } catch (Exception e) {
            log.warn("[DRAFT-MERGE] Could not enrich conflict axioms: {}", e.getMessage());
            return conflicts;
        }
    }

    private OWLOntology buildOursOntology(String projectId, String userId, String baselineRdf) throws Exception {
        OWLOntology ours = mergeService.loadOntologyFromRdf(baselineRdf);
        var manager = ours.getOWLOntologyManager();

        String draftGraph = datasetService.getDraftGraphUri(projectId, userId);
        String draftRdf = datasetService.exportNamedGraph(projectId, draftGraph, RDFFormat.RDFXML);
        if (draftRdf != null && !draftRdf.isBlank()) {
            OWLOntology draftDelta = mergeService.loadOntologyFromRdf(draftRdf);
            for (OWLAxiom axiom : draftDelta.getAxioms()) {
                manager.addAxiom(ours, axiom);
            }
        }

        for (String deleted : datasetService.getDraftDeletedIris(projectId, userId)) {
            mergeService.removeEntityFromOntology(ours, IRI.create(deleted));
        }
        return ours;
    }

    private String summarizeAxioms(OWLOntology ontology, IRI entityIRI) {
        return ontology.getAxioms().stream()
                .filter(ax -> ax.getSignature().stream().anyMatch(e -> e.getIRI().equals(entityIRI)))
                .limit(8)
                .map(OWLAxiom::toString)
                .collect(Collectors.joining("\n"));
    }

    private static String sanitizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return "anonymous";
        }
        return userId.replaceAll("[^a-zA-Z0-9._@-]", "_");
    }
}
