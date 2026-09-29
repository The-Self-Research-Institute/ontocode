package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.util.SparqlSafety;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class RollbackMutationPlanner {

    static final String RDFS_SUBCLASSOF = "http://www.w3.org/2000/01/rdf-schema#subClassOf";
    static final String RDFS_SUBPROPERTYOF = "http://www.w3.org/2000/01/rdf-schema#subPropertyOf";
    static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    static final String RDFS_DOMAIN = "http://www.w3.org/2000/01/rdf-schema#domain";
    static final String RDFS_RANGE = "http://www.w3.org/2000/01/rdf-schema#range";
    static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    static final String RDFS_COMMENT = "http://www.w3.org/2000/01/rdf-schema#comment";
    static final String OWL_THING = "http://www.w3.org/2002/07/owl#Thing";

    public enum Kind { CREATE, DELETE, GROUPED_MODIFY, SINGLE }

    public record Plan(List<MutationOp> mutations, List<String> rawUpdates, Set<String> handledSubChangeIds,
                       List<String> unsupported) {
        public boolean isEmpty() {
            return mutations.isEmpty() && rawUpdates.isEmpty();
        }
    }

    private static final class Builder {
        final List<MutationOp> mutations = new ArrayList<>();
        final List<String> rawUpdates = new ArrayList<>();
        final Set<String> handled = new LinkedHashSet<>();
        final List<String> unsupported = new ArrayList<>();

        Plan build() {
            return new Plan(List.copyOf(mutations), List.copyOf(rawUpdates), Set.copyOf(handled), List.copyOf(unsupported));
        }
    }

    public static Kind kindOf(HistoryChange change) {
        String op = lower(change.getOperationType());
        if (entityType(op) != null && op.startsWith("create")) {
            return Kind.CREATE;
        }
        if (entityType(op) != null && op.startsWith("delete")) {
            return Kind.DELETE;
        }
        return change.getSubChanges() != null && !change.getSubChanges().isEmpty() ? Kind.GROUPED_MODIFY : Kind.SINGLE;
    }

    public static List<HistoryChange.SubChange> active(HistoryChange change) {
        List<HistoryChange.SubChange> result = new ArrayList<>();
        if (change.getSubChanges() != null) {
            for (HistoryChange.SubChange sc : change.getSubChanges()) {
                if (!sc.isReverted()) {
                    result.add(sc);
                }
            }
        }
        return result;
    }

    public static List<HistoryChange.SubChange> revertedBy(HistoryChange change, String auditId) {
        List<HistoryChange.SubChange> result = new ArrayList<>();
        if (change.getSubChanges() != null) {
            for (HistoryChange.SubChange sc : change.getSubChanges()) {
                if (sc.isReverted() && (auditId == null || auditId.equals(sc.getRevertedAuditId()))) {
                    result.add(sc);
                }
            }
        }
        return result;
    }

    public Plan planUndo(HistoryChange change, List<HistoryChange.SubChange> subChanges) {
        Builder plan = new Builder();
        String entity = change.getEntityIRI();
        switch (kindOf(change)) {
            case CREATE -> {
                plan.mutations.add(entityOp("delete" + entityType(lower(change.getOperationType())), entity, null, null));
                subChanges.forEach(sc -> plan.handled.add(sc.getId()));
            }
            case DELETE -> {
                List<HistoryChange.SubChange> remaining = new ArrayList<>(subChanges);
                plan.mutations.add(recreateOp(change, remaining, plan));
                remaining.forEach(sc -> addSubChange(plan, entity, sc, true));
            }
            case GROUPED_MODIFY -> subChanges.forEach(sc -> addSubChange(plan, entity, sc, true));
            case SINGLE -> planSingle(plan, change, true);
        }
        return plan.build();
    }

    public Plan planRedo(HistoryChange change, List<HistoryChange.SubChange> subChanges) {
        Builder plan = new Builder();
        String entity = change.getEntityIRI();
        switch (kindOf(change)) {
            case CREATE -> {
                String type = entityType(lower(change.getOperationType()));
                String parent = forwardParent(subChanges, type);
                plan.mutations.add(entityOp("create" + type, entity, labelOf(change), parent));
                for (HistoryChange.SubChange sc : subChanges) {
                    if (parent != null && parent.equals(sc.getNewValue()) && isParentPredicate(sc.getPredicate())) {
                        plan.handled.add(sc.getId());
                    } else {
                        addSubChange(plan, entity, sc, false);
                    }
                }
            }
            case DELETE -> {
                plan.mutations.add(entityOp("delete" + entityType(lower(change.getOperationType())), entity, null, null));
                subChanges.forEach(sc -> plan.handled.add(sc.getId()));
            }
            case GROUPED_MODIFY -> subChanges.forEach(sc -> addSubChange(plan, entity, sc, false));
            case SINGLE -> planSingle(plan, change, false);
        }
        return plan.build();
    }

    public Plan planSubChanges(HistoryChange change, List<HistoryChange.SubChange> subChanges, boolean undo) {
        Builder plan = new Builder();
        subChanges.forEach(sc -> addSubChange(plan, change.getEntityIRI(), sc, undo));
        return plan.build();
    }

    public Plan planSubChange(HistoryChange change, HistoryChange.SubChange subChange, boolean undo) {
        Builder plan = new Builder();
        addSubChange(plan, change.getEntityIRI(), subChange, undo);
        return plan.build();
    }

    public MutationOp subChangeOp(String entity, HistoryChange.SubChange sc, boolean undo) {
        boolean add = undo != sc.isAddition();
        String value = sc.isAddition() ? sc.getNewValue() : sc.getOldValue();
        String predicate = sc.getPredicate();
        if (predicate == null || value == null || value.isEmpty()) {
            return null;
        }
        return switch (predicate) {
            case RDFS_SUBCLASSOF -> targetOp(add ? "addSubClassOf" : "deleteSubClassOf", entity, value);
            case RDFS_SUBPROPERTYOF -> targetOp(add ? "addSubPropertyOf" : "deleteSubPropertyOf", entity, value);
            case RDFS_DOMAIN -> targetOp(add ? "addPropertyDomain" : "deletePropertyDomain", entity, value);
            case RDFS_RANGE -> targetOp(add ? "addPropertyRange" : "deletePropertyRange", entity, value);
            case RDF_TYPE -> new MutationOp(add ? "addClassAssertion" : "removeClassAssertion", entity, null, null,
                    null, null, null, value, null, null, null, null, null, null, null);
            default -> annotationOp(entity, sc, add, value);
        };
    }

    private MutationOp annotationOp(String entity, HistoryChange.SubChange sc, boolean add, String value) {
        String property = sc.getAnnotationProperty();
        if (property == null || property.isEmpty()) {
            return null;
        }
        return new MutationOp(add ? "addAnnotation" : "deleteAnnotation", entity, null, null, property, value,
                null, null, null, null, null, null, null, null, null);
    }

    private void addSubChange(Builder plan, String entity, HistoryChange.SubChange sc, boolean undo) {
        MutationOp op = subChangeOp(entity, sc, undo);
        if (op != null) {
            plan.mutations.add(op);
            plan.handled.add(sc.getId());
            return;
        }
        String raw = genericRawUpdate(entity, sc, undo);
        if (raw != null) {
            plan.rawUpdates.add(raw);
            plan.handled.add(sc.getId());
        } else {
            plan.unsupported.add(sc.getPredicate());
        }
    }

    private static String genericRawUpdate(String entity, HistoryChange.SubChange sc, boolean undo) {
        boolean add = undo != sc.isAddition();
        String value = sc.isAddition() ? sc.getNewValue() : sc.getOldValue();
        if (sc.getPredicate() == null || value == null || value.isEmpty() || entity == null) {
            return null;
        }
        String subject = "<" + SparqlSafety.safeIri(entity) + ">";
        String predicate = "<" + SparqlSafety.safeIri(sc.getPredicate()) + ">";
        if (!add) {
            return "DELETE { " + subject + " " + predicate + " ?o } WHERE { " + subject + " " + predicate
                    + " ?o FILTER(STR(?o) = " + stringLiteral(value) + ") }";
        }
        return looksLikeIri(value)
                ? "INSERT DATA { " + subject + " " + predicate + " <" + SparqlSafety.safeIri(value) + "> }"
                : null;
    }

    private MutationOp recreateOp(HistoryChange change, List<HistoryChange.SubChange> remaining, Builder plan) {
        String type = entityType(lower(change.getOperationType()));
        String parentPredicate = parentPredicate(type);
        String parent = parentPredicate != null ? consumeRemoved(remaining, parentPredicate, plan) : null;
        if ("Class".equals(type) || "Individual".equals(type)) {
            parent = parent != null ? parent : OWL_THING;
        }
        return entityOp("create" + type, change.getEntityIRI(), labelOf(change), parent);
    }

    private static String consumeRemoved(List<HistoryChange.SubChange> subChanges, String predicate, Builder plan) {
        Iterator<HistoryChange.SubChange> it = subChanges.iterator();
        while (it.hasNext()) {
            HistoryChange.SubChange sc = it.next();
            if (predicate.equals(sc.getPredicate()) && !sc.isAddition() && sc.getOldValue() != null && !sc.getOldValue().isEmpty()) {
                it.remove();
                plan.handled.add(sc.getId());
                return sc.getOldValue();
            }
        }
        return null;
    }

    private static String forwardParent(List<HistoryChange.SubChange> subChanges, String type) {
        String predicate = parentPredicate(type);
        if (predicate == null) {
            return "Class".equals(type) || "Individual".equals(type) ? OWL_THING : null;
        }
        for (HistoryChange.SubChange sc : subChanges) {
            if (predicate.equals(sc.getPredicate()) && sc.isAddition() && sc.getNewValue() != null) {
                return sc.getNewValue();
            }
        }
        return "Class".equals(type) || "Individual".equals(type) ? OWL_THING : null;
    }

    private static boolean isParentPredicate(String predicate) {
        return RDFS_SUBCLASSOF.equals(predicate) || RDFS_SUBPROPERTYOF.equals(predicate) || RDF_TYPE.equals(predicate);
    }

    private static String parentPredicate(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "Class" -> RDFS_SUBCLASSOF;
            case "Individual" -> RDF_TYPE;
            case "ObjectProperty", "DataProperty", "AnnotationProperty" -> RDFS_SUBPROPERTYOF;
            default -> null;
        };
    }

    private MutationOp entityOp(String opType, String entity, String label, String parent) {
        if ("createIndividual".equals(opType)) {
            return new MutationOp(opType, entity, label, null, null, null, null, parent, null, null, null, null, null, null, null);
        }
        return new MutationOp(opType, entity, label, parent, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static MutationOp targetOp(String opType, String entity, String target) {
        return new MutationOp(opType, entity, null, null, null, null, target, null, null, null, null, null, null, null, null);
    }

    private void planSingle(Builder plan, HistoryChange change, boolean undo) {
        String op = lower(change.getOperationType());
        String entity = change.getEntityIRI();
        String oldValue = change.getOldValue();
        String newValue = change.getNewValue();
        if (op.equals("addsubclassof") || op.equals("removesubclassof")) {
            addRawParent(plan, "rdfs:subClassOf", entity, op.startsWith("add"), undo, oldValue, newValue);
        } else if (op.equals("addstatement") || op.equals("removestatement")) {
            if (RDFS_SUBPROPERTYOF.equals(change.getAnnotationProperty())) {
                addRawParent(plan, "rdfs:subPropertyOf", entity, op.startsWith("add"), undo, oldValue, newValue);
            } else {
                plan.unsupported.add(change.getAnnotationProperty());
            }
        } else if (op.contains("annotation") || op.contains("label") || op.contains("comment")) {
            planSingleAnnotation(plan, change, op, undo);
        } else {
            plan.unsupported.add(change.getOperationType());
        }
    }

    private void addRawParent(Builder plan, String predicateQName, String entity, boolean wasAdded, boolean undo,
                              String oldValue, String newValue) {
        String parent = wasAdded ? newValue : oldValue;
        if (entity == null || parent == null || parent.isEmpty() || "null".equalsIgnoreCase(parent)) {
            plan.unsupported.add(predicateQName);
            return;
        }
        boolean insert = undo != wasAdded;
        plan.rawUpdates.add("PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
                + (insert ? "INSERT DATA { " : "DELETE DATA { ")
                + "<" + SparqlSafety.safeIri(entity) + "> " + predicateQName + " <" + SparqlSafety.safeIri(parent) + "> }");
    }

    private void planSingleAnnotation(Builder plan, HistoryChange change, String op, boolean undo) {
        String property = change.getAnnotationProperty() != null && !change.getAnnotationProperty().isEmpty()
                ? change.getAnnotationProperty() : annotationPropertyFor(op);
        String entity = change.getEntityIRI();
        String oldValue = change.getOldValue();
        String newValue = change.getNewValue();
        boolean hasOld = oldValue != null && !oldValue.isEmpty();
        boolean hasNew = newValue != null && !newValue.isEmpty();
        if (hasOld && hasNew) {
            String target = undo ? oldValue : newValue;
            String current = undo ? newValue : oldValue;
            plan.mutations.add(new MutationOp("updateAnnotation", entity, null, null, property, target,
                    null, null, null, null, null, current, null, null, null));
        } else if (op.startsWith("add") || op.startsWith("create")) {
            addAnnotationValue(plan, entity, property, newValue, !undo);
        } else if (op.startsWith("remove") || op.startsWith("delete")) {
            addAnnotationValue(plan, entity, property, oldValue, undo);
        } else {
            plan.unsupported.add(change.getOperationType());
        }
    }

    private static void addAnnotationValue(Builder plan, String entity, String property, String value, boolean add) {
        if (value == null || value.isEmpty()) {
            plan.unsupported.add(property);
            return;
        }
        plan.mutations.add(new MutationOp(add ? "addAnnotation" : "deleteAnnotation", entity, null, null, property,
                value, null, null, null, null, null, null, null, null, null));
    }

    static String entityType(String opLower) {
        if (opLower == null) {
            return null;
        }
        String rest = opLower.startsWith("create") ? opLower.substring(6)
                : opLower.startsWith("delete") ? opLower.substring(6) : null;
        if (rest == null) {
            return null;
        }
        return switch (rest) {
            case "class" -> "Class";
            case "objectproperty" -> "ObjectProperty";
            case "dataproperty", "datatypeproperty" -> "DataProperty";
            case "annotationproperty" -> "AnnotationProperty";
            case "datatype" -> "Datatype";
            case "individual" -> "Individual";
            default -> null;
        };
    }

    private static String annotationPropertyFor(String opLower) {
        if (opLower.contains("comment")) return RDFS_COMMENT;
        if (opLower.contains("seealso")) return "http://www.w3.org/2000/01/rdf-schema#seeAlso";
        if (opLower.contains("isdefinedby")) return "http://www.w3.org/2000/01/rdf-schema#isDefinedBy";
        return RDFS_LABEL;
    }

    private static String labelOf(HistoryChange change) {
        if (change.getEntityLabel() != null && !change.getEntityLabel().isBlank()) {
            return change.getEntityLabel();
        }
        String iri = change.getEntityIRI();
        if (iri == null) {
            return null;
        }
        String[] parts = iri.split("[#/]");
        return parts.length > 0 ? parts[parts.length - 1] : iri;
    }

    static boolean looksLikeIri(String value) {
        return value.matches("^[A-Za-z][A-Za-z0-9+.-]*:[^\\s<>\"]+$") && !value.contains(" ");
    }

    static String stringLiteral(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
