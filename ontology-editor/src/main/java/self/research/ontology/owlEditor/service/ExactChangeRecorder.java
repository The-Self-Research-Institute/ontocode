package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Model;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.delta.DeltaCapture;
import self.research.ontology.owlEditor.service.delta.ExactDelta;
import self.research.ontology.owlEditor.service.delta.ExactSeeds;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

@Slf4j
@Service
public class ExactChangeRecorder {

    private final SparqlDatasetService datasetService;
    private final HistoryChangeRepository history;
    private final OntologyHistoryService historyService;

    @Autowired(required = false) @Nullable
    private DesktopOwlApiMutationService desktopOwlApi;

    @Autowired(required = false) @Nullable
    private ProjectOntologyCache ontologyCache;

    public ExactChangeRecorder(SparqlDatasetService datasetService, HistoryChangeRepository history,
                               OntologyHistoryService historyService) {
        this.datasetService = datasetService;
        this.history = history;
        this.historyService = historyService;
    }

    public String recordGrouped(String projectId, boolean draft, String userId, String username, List<MutationOp> ops,
                                Runnable apply) {
        return recordAround(projectId, draft, userId, ops, apply, () -> {
            try {
                return historyService.recordGroupedMutations(projectId, userId, username, ops, draft);
            } catch (RuntimeException e) {
                log.warn("[MUTATION] Failed to record mutation history for project {}: {}", projectId, e.getMessage());
                return null;
            }
        });
    }

    public static java.util.Map<String, Object> appliedBody(boolean draft, String message, String changeSetId) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("success", true);
        body.put("draft", draft);
        body.put("message", message);
        body.put("undoable", changeSetId != null);
        if (changeSetId != null) {
            body.put("changeSetId", changeSetId);
        }
        return body;
    }

    public String recordAround(String projectId, boolean draft, String userId, List<MutationOp> ops, Runnable apply,
                               Supplier<String> record) {
        List<MutationOp> eligible = ExactSeeds.eligible(ops);
        boolean inMemory = !draft && !eligible.isEmpty() && inMemoryIsAuthoritative(projectId);
        Optional<Model> memoryBefore = inMemory ? OwlApiModelSnapshot.of(ontologyCache, projectId) : Optional.empty();
        Optional<DeltaCapture> capture = inMemory ? Optional.empty() : begin(projectId, draft, userId, eligible);
        apply.run();
        Optional<ExactDelta> delta = inMemory ? memoryDelta(projectId, memoryBefore) : capture.flatMap(this::finish);
        String changeSetId = record.get();
        if (changeSetId != null && delta.isPresent()) {
            attach(projectId, changeSetId, ExactSeeds.groupIris(eligible), delta.get());
        }
        return changeSetId;
    }

    private boolean inMemoryIsAuthoritative(String projectId) {
        return desktopOwlApi != null && ontologyCache != null && desktopOwlApi.isAuthoritative(projectId);
    }

    private Optional<ExactDelta> memoryDelta(String projectId, Optional<Model> before) {
        if (before.isEmpty() || !inMemoryIsAuthoritative(projectId)) {
            return Optional.empty();
        }
        try {
            return OwlApiModelSnapshot.of(ontologyCache, projectId).flatMap(after -> ExactOrigins.deltaBetween(before.get(), after));
        } catch (RuntimeException e) {
            log.warn("[UNDO] Could not work out the exact change of the in-memory ontology: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<DeltaCapture> begin(String projectId, boolean draft, String userId, List<MutationOp> eligible) {
        if (eligible.isEmpty()) {
            return Optional.empty();
        }
        try {
            SparqlDatasetService.ProjectGraphTarget target = datasetService.graphTarget(projectId);
            String graph = draft ? datasetService.getDraftGraphUri(projectId, userId) : target.graphUri();
            if (ExactSeeds.needsWholeGraph(eligible)) {
                return DeltaCapture.beginWholeGraph(target.repository(), graph);
            }
            return DeltaCapture.begin(target.repository(), graph, ExactSeeds.seeds(eligible),
                    ExactSeeds.incomingSeeds(eligible));
        } catch (RuntimeException e) {
            log.warn("[UNDO] Could not read the graph before the change for project {}: {}", projectId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ExactDelta> finish(DeltaCapture capture) {
        try {
            return capture.finish();
        } catch (RuntimeException e) {
            log.warn("[UNDO] Could not read the graph after the change: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void attach(String projectId, String changeSetId, Set<String> groupIris, ExactDelta delta) {
        try {
            List<HistoryChange> entries = new ArrayList<>();
            for (HistoryChange entry : history.findByProjectIdAndChangeSetIdOrderByTimestampDesc(projectId, changeSetId)) {
                if (entry.getEntityIRI() != null && groupIris.contains(entry.getEntityIRI())) {
                    entries.add(entry);
                }
            }
            if (entries.isEmpty()) {
                return;
            }
            HistoryChange carrier = entries.get(entries.size() - 1);
            carrier.setExactDeleted(delta.deleted().isEmpty() ? null : ExactDelta.write(delta.deleted()));
            carrier.setExactInserted(delta.inserted().isEmpty() ? null : ExactDelta.write(delta.inserted()));
            carrier.setExactCovers(entries.size() - 1);
            for (HistoryChange entry : entries) {
                if (entry != carrier) {
                    entry.setCoveredBy(carrier.getId());
                }
            }
            history.saveAll(entries);
        } catch (RuntimeException e) {
            log.warn("[UNDO] Could not store the exact change data for {}: {}", changeSetId, e.getMessage());
        }
    }
}
