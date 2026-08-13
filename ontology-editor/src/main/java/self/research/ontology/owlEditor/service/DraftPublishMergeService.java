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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final MainGraphRevisionService revisionService;

    public DraftPublishMergeService(SparqlDatasetService datasetService,
                                    StorageManager storageManager,
                                    OntologyMergeService mergeService,
                                    DraftSessionRepository sessionRepository,
                                    MainGraphRevisionService revisionService) {
        this.datasetService = datasetService;
        this.storageManager = storageManager;
        this.mergeService = mergeService;
        this.sessionRepository = sessionRepository;
        this.revisionService = revisionService;
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

    public void publishWithThreeWayMerge(String projectId,
                                         String userId,
                                         DraftPublishAnalysis analysis,
                                         Map<String, ConflictResolution> resolutions) throws Exception {
        Optional<DraftSession> sessionOpt = sessionRepository.findByProjectIdAndUserId(projectId, userId);
        if (sessionOpt.isEmpty() || sessionOpt.get().getBaselineSnapshotPath() == null) {
            log.warn("[DRAFT-MERGE] No baseline snapshot — publishing via MOVE GRAPH");
            datasetService.moveDraftToMain(projectId, userId);
            return;
        }

        Path baselinePath = storageManager.projectDir(projectId)
                .resolve(sessionOpt.get().getBaselineSnapshotPath());
        if (!Files.exists(baselinePath)) {
            log.warn("[DRAFT-MERGE] Baseline file missing — publishing via MOVE GRAPH");
            datasetService.moveDraftToMain(projectId, userId);
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

    public Map<String, Object> analyzePull(String projectId, String userId) throws Exception {
        Optional<DraftSession> sessionOpt = sessionRepository.findByProjectIdAndUserId(projectId, userId);
        if (sessionOpt.isEmpty()) {
            return noChangesResult(true);
        }

        DraftSession session = sessionOpt.get();
        String baselineRdf = readOrEstablishBaseline(projectId, userId, session);
        if (baselineRdf == null) {

            return noChangesResult(false);
        }

        OWLOntology baseline = mergeService.loadOntologyFromRdf(baselineRdf);
        String mainRdf = datasetService.exportNamedGraph(projectId, datasetService.getGraphUri(projectId), RDFFormat.RDFXML);
        OWLOntology theirs = mergeService.loadOntologyFromRdf(mainRdf);
        OWLOntology ours = buildOursOntology(projectId, userId, baselineRdf);

        Set<String> mainTouched = mergeService.collectTouchedIris(baseline, theirs);
        Set<String> draftTouched = mergeService.collectTouchedIris(baseline, ours);
        Set<String> conflictIris = new LinkedHashSet<>(mainTouched);
        conflictIris.retainAll(draftTouched);

        List<Map<String, Object>> safeChanges = new ArrayList<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (String iriStr : mainTouched) {
            IRI iri = IRI.create(iriStr);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entityIri", iriStr);
            row.put("entityLabel", localName(iriStr));
            row.put("publicAxioms", summarizeAxioms(theirs, iri));
            if (conflictIris.contains(iriStr)) {
                row.put("yourAxioms", summarizeAxioms(ours, iri));
                conflicts.add(row);
            } else {
                safeChanges.add(row);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasChanges", !mainTouched.isEmpty());
        result.put("hasConflicts", !conflicts.isEmpty());
        result.put("safeChanges", safeChanges);
        result.put("conflicts", conflicts);
        result.put("noBaseline", false);
        return result;
    }

    public Map<String, Object> applyPull(String projectId, String userId,
                                         Map<String, ConflictResolution> resolutions) throws Exception {
        Optional<DraftSession> sessionOpt = sessionRepository.findByProjectIdAndUserId(projectId, userId);
        if (sessionOpt.isEmpty()) {
            throw new IllegalStateException("No draft session found for project " + projectId);
        }
        DraftSession session = sessionOpt.get();
        String baselineRdf = readOrEstablishBaseline(projectId, userId, session);
        if (baselineRdf == null) {
            return Map.of("success", true, "mergedCount", 0, "conflictsResolved", 0,
                    "message", "Draft baseline established — nothing to pull yet");
        }

        OWLOntology baseline = mergeService.loadOntologyFromRdf(baselineRdf);
        String mainRdf = datasetService.exportNamedGraph(projectId, datasetService.getGraphUri(projectId), RDFFormat.RDFXML);
        OWLOntology theirs = mergeService.loadOntologyFromRdf(mainRdf);
        OWLOntology ours = buildOursOntology(projectId, userId, baselineRdf);

        Set<String> mainTouched = mergeService.collectTouchedIris(baseline, theirs);
        Set<String> draftTouched = mergeService.collectTouchedIris(baseline, ours);
        Set<String> conflictIris = new LinkedHashSet<>(mainTouched);
        conflictIris.retainAll(draftTouched);

        OWLOntology mergedDraft = mergeService.mergeDraftPublishThreeWay(
                baseline, theirs, ours, conflictIris, resolutions);

        String mergedRdf = mergeService.saveOntologyToRdfXml(mergedDraft);
        datasetService.replaceNamedGraphFromRdf(
                projectId, datasetService.getDraftGraphUri(projectId, userId), mergedRdf, RDFFormat.RDFXML);

        advanceBaseline(projectId, userId, session);

        int mergedCount = mainTouched.size();
        int conflictCount = conflictIris.size();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("mergedCount", mergedCount);
        result.put("conflictsResolved", conflictCount);
        result.put("message", mergedCount == 0
                ? "Your draft is already up to date with public"
                : "Pulled " + mergedCount + " public change(s) into your draft"
                        + (conflictCount == 0 ? "" : " (" + conflictCount + " conflict(s) resolved)"));
        return result;
    }

    private String readOrEstablishBaseline(String projectId, String userId, DraftSession session) throws Exception {
        String snapshotPath = session.getBaselineSnapshotPath();
        Path baselinePath = snapshotPath != null ? storageManager.projectDir(projectId).resolve(snapshotPath) : null;
        if (baselinePath != null && Files.exists(baselinePath)) {
            return Files.readString(baselinePath);
        }

        log.warn("[DRAFT-MERGE] No baseline snapshot for project {} user {} — establishing one now",
                projectId, userId);
        advanceBaseline(projectId, userId, session);
        return null;
    }

    private void advanceBaseline(String projectId, String userId, DraftSession session) throws IOException {
        String newSnapshotPath = captureBaselineSnapshot(projectId, userId);
        session.setBaselineSnapshotPath(newSnapshotPath);
        session.setBaselineMainRevision(revisionService.getRevision(projectId));
        session.setBaselineMainTripleCount(datasetService.countMainGraphTriples(projectId));
        session.setBaselineAt(LocalDateTime.now());
        sessionRepository.save(session);
    }

    private static Map<String, Object> noChangesResult(boolean noBaseline) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasChanges", false);
        result.put("hasConflicts", false);
        result.put("safeChanges", List.of());
        result.put("conflicts", List.of());
        result.put("noBaseline", noBaseline);
        return result;
    }

    private static String localName(String iriStr) {
        int hash = iriStr.lastIndexOf('#');
        int slash = iriStr.lastIndexOf('/');
        int idx = Math.max(hash, slash);
        return idx >= 0 && idx < iriStr.length() - 1 ? iriStr.substring(idx + 1) : iriStr;
    }

    private OWLOntology buildOursOntology(String projectId, String userId, String baselineRdf) throws Exception {
        String draftGraph = datasetService.getDraftGraphUri(projectId, userId);
        String draftRdf = datasetService.exportNamedGraph(projectId, draftGraph, RDFFormat.RDFXML);
        if (draftRdf != null && !draftRdf.isBlank()) {
            return mergeService.loadOntologyFromRdf(draftRdf);
        }
        return mergeService.loadOntologyFromRdf(baselineRdf);
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
