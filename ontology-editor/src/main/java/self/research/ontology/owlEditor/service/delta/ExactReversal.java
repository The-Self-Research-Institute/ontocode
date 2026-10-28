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

    private static final String RULE_CHANGE = "ruleChange";
    private static final String SWRL_VARIABLE = "http://www.w3.org/2003/11/swrl#Variable";

    public static boolean isRuleChange(HistoryChange change) {
        return RULE_CHANGE.equals(change.getOperationType());
    }

    private static final Set<String> IDEMPOTENT_TYPES = Set.of(SWRL_VARIABLE,
            "http://www.w3.org/2002/07/owl#Class", "http://www.w3.org/2002/07/owl#ObjectProperty",
            "http://www.w3.org/2002/07/owl#DatatypeProperty", "http://www.w3.org/2002/07/owl#AnnotationProperty",
            "http://www.w3.org/2000/01/rdf-schema#Datatype", "http://www.w3.org/2002/07/owl#NamedIndividual");

    private static boolean isVariableTyping(Statement statement) {
        return statement.getSubject().isIRI() && RDF_TYPE.equals(statement.getPredicate().stringValue())
                && IDEMPOTENT_TYPES.contains(statement.getObject().stringValue());
    }

    private static Model withoutVariables(Model model) {
        Model kept = new org.eclipse.rdf4j.model.impl.LinkedHashModel();
        model.stream().filter(statement -> !isVariableTyping(statement)).forEach(kept::add);
        return kept;
    }

    public static Optional<ExactDelta> checkedDeltaOf(HistoryChange change) {
        Optional<ExactDelta> delta = deltaOf(change);
        if (delta.isEmpty() || !isRuleChange(change)) {
            return delta;
        }
        return Optional.of(new ExactDelta(withoutVariables(delta.get().deleted()), withoutVariables(delta.get().inserted())));
    }

    private static String typing(Statement statement) {
        return "<" + statement.getSubject().stringValue() + "> <" + RDF_TYPE + "> <" + statement.getObject().stringValue() + ">";
    }

    private static List<String> variableUpdates(ExactDelta delta, boolean undo) {
        List<String> updates = new java.util.ArrayList<>();
        Model create = undo ? delta.deleted() : delta.inserted();
        Model drop = undo ? delta.inserted() : delta.deleted();
        create.stream().filter(ExactReversal::isVariableTyping).forEach(statement ->
                updates.add("INSERT DATA { " + typing(statement) + " }"));
        drop.stream().filter(ExactReversal::isVariableTyping).forEach(statement -> {
            String entity = "<" + statement.getSubject().stringValue() + ">";
            updates.add("DELETE { " + typing(statement) + " } WHERE { " + typing(statement)
                    + " FILTER NOT EXISTS { ?s ?p " + entity + " } FILTER NOT EXISTS { ?s " + entity + " ?o } "
                    + "FILTER NOT EXISTS { " + entity + " ?q ?o FILTER(?q != <" + RDF_TYPE + ">) } }");
        });
        return updates;
    }

    public static Plan plan(HistoryChange change, List<HistoryChange.SubChange> subChanges, boolean undo) {
        Set<String> handled = new HashSet<>();
        for (HistoryChange.SubChange sub : subChanges) {
            handled.add(sub.getId());
        }
        Optional<ExactDelta> delta = deltaOf(change);
        List<String> variableUpdates = delta.isPresent() && isRuleChange(change)
                ? variableUpdates(delta.get(), undo) : List.of();
        if (!variableUpdates.isEmpty()) {
            delta = checkedDeltaOf(change);
        }
        if (delta.isEmpty()) {
            return new Plan(List.of(), List.of(), handled, List.of());
        }
        List<String> updates = new java.util.ArrayList<>();
        for (ExactDelta batch : DeltaBatches.split(delta.get(), Limit.DELTA_BATCH_TRIPLES.value())) {
            if (!batch.isEmpty()) {
                updates.add(undo ? DeltaSparql.reverse(batch) : DeltaSparql.forward(batch));
            }
        }
        if (undo) {
            updates.addAll(variableUpdates);
        } else {
            updates.addAll(0, variableUpdates.stream().filter(update -> update.startsWith("INSERT")).toList());
            updates.addAll(variableUpdates.stream().filter(update -> !update.startsWith("INSERT")).toList());
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
