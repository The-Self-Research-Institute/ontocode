package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.service.delta.DeltaCapture;
import self.research.ontology.owlEditor.service.delta.ExactDelta;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class CitationHistory {

    private final SparqlDatasetService datasetService;
    private final OntologyHistoryService historyService;
    private final ProjectWriteLockRegistry locks;

    public CitationHistory(SparqlDatasetService datasetService, OntologyHistoryService historyService,
                           ProjectWriteLockRegistry locks) {
        this.datasetService = datasetService;
        this.historyService = historyService;
        this.locks = locks;
    }

    public record Entry(String operationType, String citationIri, String label, String oldText, String newText,
                        String description) {}

    public void around(String projectId, RequestActor actor, Entry entry, List<String> seeds, List<String> incoming,
                       Runnable apply) {
        locks.exclusive(projectId, () -> {
            recordAround(projectId, actor, entry, seeds, incoming, apply);
            return null;
        });
    }

    private void recordAround(String projectId, RequestActor actor, Entry entry, List<String> seeds, List<String> incoming,
                              Runnable apply) {
        Optional<DeltaCapture> capture = begin(projectId, seeds, incoming);
        apply.run();
        DeltaCapture.Finish finished = capture.map(this::finish).orElse(new DeltaCapture.Finish(Optional.empty(), false));
        if (finished.unchanged()) {
            return;
        }
        Optional<ExactDelta> delta = finished.delta();
        ChangeOrigin origin = ChangeOrigin.manual();
        if (delta.isPresent()) {
            origin = origin.withExact(new ExactCarrier(delta.get()));
        }
        historyService.recordEdit(projectId, actor.userId(), actor.username(), entry.operationType(), entry.citationIri(),
                entry.label(), entry.oldText(), entry.newText(), entry.description(), null, null, false, origin);
    }

    private Optional<DeltaCapture> begin(String projectId, List<String> seeds, List<String> incoming) {
        try {
            SparqlDatasetService.ProjectGraphTarget target = datasetService.graphTarget(projectId);
            return DeltaCapture.begin(target.repository(), target.graphUri(), seeds, incoming);
        } catch (RuntimeException e) {
            log.warn("[UNDO] Could not read the graph before the citation change for project {}: {}", projectId, e.getMessage());
            return Optional.empty();
        }
    }

    private DeltaCapture.Finish finish(DeltaCapture capture) {
        try {
            return capture.finishDetailed();
        } catch (RuntimeException e) {
            log.warn("[UNDO] Could not read the graph after the citation change: {}", e.getMessage());
            return new DeltaCapture.Finish(Optional.empty(), false);
        }
    }
}
