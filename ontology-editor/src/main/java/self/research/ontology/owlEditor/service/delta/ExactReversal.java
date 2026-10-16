package self.research.ontology.owlEditor.service.delta;

import self.research.ontology.owlEditor.model.HistoryChange;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import self.research.ontology.owlEditor.service.DocumentReversal;
import self.research.ontology.owlEditor.service.Limit;
import self.research.ontology.owlEditor.service.OntologyMergeService;
import self.research.ontology.owlEditor.service.RollbackAction;
import self.research.ontology.owlEditor.service.RollbackMutationPlanner.Plan;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class ExactReversal {

    private static final String VERSION_IRI = "http://www.w3.org/2002/07/owl#versionIRI";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String OWL_ONTOLOGY = "http://www.w3.org/2002/07/owl#Ontology";

    private ExactReversal() {
    }

    public static boolean isExact(HistoryChange change) {
        return change.carriesExactDelta() || change.getCoveredBy() != null;
    }

    public static boolean deletesEntity(HistoryChange change) {
        return isExact(change) && isEntityOp(change, "delete");
    }

    public static boolean createsEntity(HistoryChange change) {
        return isExact(change) && isEntityOp(change, "create");
    }

    private static boolean isEntityOp(HistoryChange change, String verb) {
        String op = change.getOperationType();
        return op != null && op.startsWith(verb) && ExactSeeds.isEntityLevel(op);
    }

    public static boolean needsWholeChangeSet(HistoryChange change) {
        return change.getCoveredBy() != null || change.getExactCovers() > 0;
    }

    public static Optional<ExactDelta> deltaOf(HistoryChange change) {
        if (!change.carriesExactDelta()) {
            return Optional.empty();
        }
        return Optional.of(ExactDelta.of(change.getExactDeleted(), change.getExactInserted()));
    }

    public static Plan plan(HistoryChange change, List<HistoryChange.SubChange> subChanges, boolean undo) {
        Set<String> handled = new HashSet<>();
        for (HistoryChange.SubChange sub : subChanges) {
            handled.add(sub.getId());
        }
        Optional<ExactDelta> delta = deltaOf(change);
        if (delta.isEmpty()) {
            return new Plan(List.of(), List.of(), handled, List.of());
        }
        List<String> updates = new java.util.ArrayList<>();
        for (ExactDelta batch : DeltaBatches.split(delta.get(), Limit.DELTA_BATCH_TRIPLES.value())) {
            updates.add(undo ? DeltaSparql.reverse(batch) : DeltaSparql.forward(batch));
        }
        List<RollbackAction> actions = new java.util.ArrayList<>();
        if (touchesOntologyHeader(delta.get())) {
            actions.add(RollbackAction.refreshOntology());
        }
        if (OntologyMergeService.MERGE_OPERATION.equals(change.getOperationType())) {
            actions.add(RollbackAction.syncStoredFile());
        }
        actions.addAll(DocumentReversal.actions(change, undo));
        return new Plan(List.of(), List.copyOf(updates), handled, List.of(), actions);
    }

    private static boolean touchesOntologyHeader(ExactDelta delta) {
        return mentionsHeader(delta.deleted()) || mentionsHeader(delta.inserted());
    }

    private static boolean mentionsHeader(Model model) {
        for (Statement statement : model) {
            if (VERSION_IRI.equals(statement.getPredicate().stringValue())
                    || (RDF_TYPE.equals(statement.getPredicate().stringValue())
                    && OWL_ONTOLOGY.equals(statement.getObject().stringValue()))) {
                return true;
            }
        }
        return false;
    }
}
