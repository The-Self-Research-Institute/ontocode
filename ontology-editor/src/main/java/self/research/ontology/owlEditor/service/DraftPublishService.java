package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.model.OntologyChange;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;
import self.research.ontology.owlEditor.repository.OntologyChangeRepository;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DraftPublishService {

    private static final Logger log = LoggerFactory.getLogger(DraftPublishService.class);

    private final DraftSessionRepository sessionRepository;
    private final MainGraphRevisionService revisionService;
    private final SparqlDatasetService datasetService;
    private final OntologyChangeRepository changeRepository;
    private final DraftPublishMergeService mergeService;

    public DraftPublishService(DraftSessionRepository sessionRepository,
                               MainGraphRevisionService revisionService,
                               SparqlDatasetService datasetService,
                               OntologyChangeRepository changeRepository,
                               DraftPublishMergeService mergeService) {
        this.sessionRepository = sessionRepository;
        this.revisionService = revisionService;
        this.datasetService = datasetService;
        this.changeRepository = changeRepository;
        this.mergeService = mergeService;
    }

    public void ensureBaseline(String projectId, String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        if (sessionRepository.findByProjectIdAndUserId(projectId, userId).isPresent()) {
            return;
        }
        DraftSession session = new DraftSession(
                projectId,
                userId,
                revisionService.getRevision(projectId),
                datasetService.countMainGraphTriples(projectId)
        );
        try {
            String snapshotPath = mergeService.captureBaselineSnapshot(projectId, userId);
            session.setBaselineSnapshotPath(snapshotPath);
        } catch (Exception e) {
            log.warn("[DRAFT] Could not capture baseline snapshot for project {} user {}: {}",
                    projectId, userId, e.getMessage());
        }
        sessionRepository.save(session);
    }

    public void clearBaseline(String projectId, String userId) {
        if (userId != null && !userId.isBlank()) {
            mergeService.deleteBaselineSnapshot(projectId, userId);
            sessionRepository.deleteByProjectIdAndUserId(projectId, userId);
        }
    }

    public DraftPublishAnalysis analyze(String projectId, String userId, List<DraftChange> userDrafts) {
        return analyze(projectId, userId, userDrafts, false);
    }

    public DraftPublishAnalysis analyze(String projectId, String userId, List<DraftChange> userDrafts,
                                        boolean enrichAxiomDetail) {
        long currentRevision = revisionService.getRevision(projectId);
        long currentTripleCount = datasetService.countMainGraphTriples(projectId);

        Optional<DraftSession> sessionOpt = sessionRepository.findByProjectIdAndUserId(projectId, userId);
        if (sessionOpt.isEmpty()) {
            return new DraftPublishAnalysis(
                    DraftPublishAnalysis.ConflictType.NONE,
                    false,
                    currentRevision,
                    currentRevision,
                    currentTripleCount,
                    currentTripleCount,
                    List.of()
            );
        }

        DraftSession session = sessionOpt.get();
        boolean mainChanged = currentRevision > session.getBaselineMainRevision()
                || currentTripleCount != session.getBaselineMainTripleCount();

        Set<String> draftIris = extractIris(userDrafts);
        List<Map<String, Object>> overlapConflicts = findOverlappingConflicts(
                projectId, userId, session.getBaselineAt(), draftIris);
        if (enrichAxiomDetail && !overlapConflicts.isEmpty()) {
            overlapConflicts = mergeService.enrichConflictsWithAxiomDetail(projectId, userId, overlapConflicts);
        }

        DraftPublishAnalysis.ConflictType type = DraftPublishAnalysis.ConflictType.NONE;
        if (!overlapConflicts.isEmpty()) {
            type = DraftPublishAnalysis.ConflictType.IRI_OVERLAP;
        } else if (mainChanged) {
            type = DraftPublishAnalysis.ConflictType.MAIN_CHANGED;
        }

        return new DraftPublishAnalysis(
                type,
                mainChanged,
                session.getBaselineMainRevision(),
                currentRevision,
                session.getBaselineMainTripleCount(),
                currentTripleCount,
                overlapConflicts
        );
    }

    private Set<String> extractIris(List<DraftChange> drafts) {
        Set<String> iris = new LinkedHashSet<>();
        for (DraftChange draft : drafts) {
            Map<String, Object> data = draft.getOperationData();
            if (data == null) {
                continue;
            }
            addIfPresent(iris, data.get("iri"));
            addIfPresent(iris, data.get("parent"));
            addIfPresent(iris, data.get("target"));
            addIfPresent(iris, data.get("classIri"));
            addIfPresent(iris, data.get("property"));
        }
        return iris;
    }

    private void addIfPresent(Set<String> iris, Object value) {
        if (value instanceof String s && !s.isBlank()) {
            iris.add(s);
        }
    }

    private List<Map<String, Object>> findOverlappingConflicts(String projectId,
                                                               String userId,
                                                               LocalDateTime baselineAt,
                                                               Set<String> draftIris) {
        if (draftIris.isEmpty()) {
            return List.of();
        }

        List<OntologyChange> recent = changeRepository
                .findByProjectIdAndTimestampAfterOrderByTimestampDesc(projectId, baselineAt);

        Map<String, OntologyChange> touchedByOthers = new LinkedHashMap<>();
        for (OntologyChange change : recent) {
            if (change.getUserId() == null || change.getUserId().equals(userId)) {
                continue;
            }
            String iri = change.getEntityIRI();
            if (iri != null && draftIris.contains(iri) && !touchedByOthers.containsKey(iri)) {
                touchedByOthers.put(iri, change);
            }
        }

        return touchedByOthers.entrySet().stream()
                .map(e -> {
                    OntologyChange c = e.getValue();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("entityIRI", e.getKey());
                    row.put("entityLabel", c.getEntityLabel());
                    row.put("changeType", c.getChangeType() != null ? c.getChangeType().name() : null);
                    row.put("changedBy", c.getUsername());
                    row.put("changedByUserId", c.getUserId());
                    row.put("description", c.getDescription());
                    row.put("timestamp", c.getTimestamp() != null ? c.getTimestamp().toString() : null);
                    return row;
                })
                .collect(Collectors.toList());
    }
}
