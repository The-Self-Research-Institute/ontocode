package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.io.StringDocumentTarget;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLRule;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.model.DraftCopyStatus;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;
import self.research.ontology.owlEditor.util.OwlAxiomSparqlWriter;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class DraftRuleTargets implements RuleMigrationService.DraftTargets {

    private final DraftSessionRepository sessionRepository;
    private final OntologyMutationService mutationService;
    private final DraftPublishMergeService publishMergeService;
    private final OntologyMergeService mergeService;

    public DraftRuleTargets(DraftSessionRepository sessionRepository, @Lazy OntologyMutationService mutationService,
                            @Lazy DraftPublishMergeService publishMergeService, OntologyMergeService mergeService) {
        this.sessionRepository = sessionRepository;
        this.mutationService = mutationService;
        this.publishMergeService = publishMergeService;
        this.mergeService = mergeService;
    }

    @Override
    public List<String> readyDraftUsers(String projectId) {
        return sessionRepository.findByProjectId(projectId).stream()
                .filter(session -> session.getCopyStatus() == DraftCopyStatus.READY)
                .map(DraftSession::getUserId)
                .filter(user -> user != null && !user.startsWith(PrSnapshotService.KEY_PREFIX))
                .toList();
    }

    @Override
    public void addToDraft(String projectId, String userId, Set<SWRLRule> axioms) throws Exception {
        mutationService.applyRawUpdate(projectId, OwlAxiomSparqlWriter.toInsertData(axioms), true, userId);
    }

    @Override
    public void addToBaseline(String projectId, String userId, Set<SWRLRule> axioms) throws Exception {
        Optional<String> baseline = publishMergeService.baselineOf(projectId, userId);
        if (baseline.isEmpty()) {
            return;
        }
        OWLOntology ontology = mergeService.loadOntologyFromRdf(baseline.get());
        boolean changed = false;
        for (SWRLRule axiom : axioms) {
            if (!ontology.containsAxiom(axiom)) {
                ontology.getOWLOntologyManager().addAxiom(ontology, axiom);
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        ontology.getOWLOntologyManager().applyChange(
                new org.semanticweb.owlapi.model.SetOntologyID(ontology, mergeService.headerOf(ontology)));
        StringDocumentTarget target = new StringDocumentTarget();
        ontology.getOWLOntologyManager().saveOntology(ontology, new RDFXMLDocumentFormat(), target);
        publishMergeService.writeBaselineFor(projectId, userId, target.toString());
    }
}
