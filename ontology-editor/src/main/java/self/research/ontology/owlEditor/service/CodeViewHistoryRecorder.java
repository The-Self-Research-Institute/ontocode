package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Namespace;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
final class CodeViewHistoryRecorder {

    private static final String DESKTOP_USER_ID = "desktop-user-local";

    private final OntologyHistoryService historyService;
    private final DraftTrackingService draftTrackingService;

    CodeViewHistoryRecorder(OntologyHistoryService historyService, DraftTrackingService draftTrackingService) {
        this.historyService = historyService;
        this.draftTrackingService = draftTrackingService;
    }

    static String effectiveUserId(String userId, boolean desktopMode) {
        if (desktopMode) {
            return DESKTOP_USER_ID;
        }
        return userId != null && !userId.isBlank() ? userId : "anonymous";
    }

    static String effectiveUsername(String username) {
        return username != null && !username.isBlank() ? username : "System";
    }

    private record Actor(String projectId, String userId, String username, boolean draft, ChangeOrigin origin) {}

    private record SubjectDiff(List<Statement> added, List<Statement> removed) {}

    private record TypeChange(String createOp, Statement createType, String deleteOp, Statement deleteType) {}

    void record(String projectId, String userId, String username, Model oldModel, Model newModel, boolean draft) {
        record(projectId, userId, username, oldModel, newModel, draft, null);
    }

    void record(String projectId, String userId, String username, Model oldModel, Model newModel, boolean draft,
                ChangeOrigin origin) {
        record(projectId, userId, username, oldModel, newModel, draft, origin, true);
    }

    boolean record(String projectId, String userId, String username, Model oldModel, Model newModel, boolean draft,
                ChangeOrigin origin, boolean diffPrefixes) {
        Actor actor = new Actor(projectId, userId, username, draft, origin != null ? origin : ChangeOrigin.manual());
        if (diffPrefixes) {
            recordPrefixChanges(actor, oldModel.getNamespaces(), newModel.getNamespaces());
        }
        Set<Statement> added = new LinkedHashSet<>(newModel);
        added.removeAll(oldModel);
        Set<Statement> removed = new LinkedHashSet<>(oldModel);
        removed.removeAll(newModel);
        if (added.isEmpty() && removed.isEmpty()) {
            return false;
        }

        int namedChangeCount = (int) (added.stream().filter(this::isNamedTriple).count()
                + removed.stream().filter(this::isNamedTriple).count());
        if (namedChangeCount > Limit.CODE_VIEW_BULK_DIFF_THRESHOLD.value()) {
            String bulkDescription = "Code View save added/changed " + namedChangeCount
                    + " statements — logged as a single bulk entry rather than one per statement";
            historyService.recordEdit(projectId, userId, username,
                    "bulkPopulation", null, null, null, null,
                    bulkDescription,
                    null, null, draft, actor.origin());
            log.info("[CODE-VIEW-SAVE] Skipped per-triple change logging for bulk save ({} named changes)", namedChangeCount);
            recordSummaryDraft(actor, "bulkGraphEdit", (added.size() + removed.size()) + " statements",
                    added.size() + " added, " + removed.size() + " removed");
            return true;
        }

        List<OntologyMutationService.MutationOp> draftOps = draft && draftTrackingService != null ? new ArrayList<>() : null;
        Map<Resource, SubjectDiff> bySubject = new LinkedHashMap<>();
        int structuralChanges = groupBySubject(added, removed, bySubject);
        for (Map.Entry<Resource, SubjectDiff> entry : bySubject.entrySet()) {
            recordSubject(actor, entry.getKey(), entry.getValue(), oldModel, newModel, draftOps);
        }
        if (structuralChanges > 0) {
            historyService.recordEdit(projectId, userId, username,
                    "codeViewStructuralEdit", null, null, null, null,
                    "Code View save modified " + structuralChanges
                            + " structural axiom(s) (restrictions, unions, SWRL rules, disjoint-class lists, etc.)",
                    null, null, draft, actor.origin());
        }
        if (draftOps != null && !draftOps.isEmpty()) {
            draftTrackingService.recordDrafts(projectId, userId, username, draftOps, UUID.randomUUID().toString());
        } else if (structuralChanges > 0) {
            recordSummaryDraft(actor, "structuralGraphEdit", structuralChanges + " structural statement"
                    + (structuralChanges == 1 ? "" : "s"), "Restrictions, unions, rules or other axioms without a name");
        }
        return true;
    }

    private void recordSummaryDraft(Actor actor, String opType, String label, String value) {
        if (!actor.draft() || draftTrackingService == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", label);
        data.put("value", value);
        draftTrackingService.recordDescribed(actor.projectId(), actor.userId(), actor.username(), opType, data,
                changeSets(actor));
    }

    private static List<String> changeSets(Actor actor) {
        String changeSetId = actor.origin().changeSetId();
        return changeSetId == null ? List.of() : List.of(changeSetId);
    }

    void recordPrefixes(String projectId, String userId, String username, Map<String, String> oldPrefixes,
                        Map<String, String> newPrefixes, boolean draft, ChangeOrigin origin) {
        Actor actor = new Actor(projectId, userId, username, draft, origin != null ? origin : ChangeOrigin.manual());
        Set<Namespace> oldNamespaces = new LinkedHashSet<>();
        oldPrefixes.forEach((prefix, name) -> oldNamespaces.add(new org.eclipse.rdf4j.model.impl.SimpleNamespace(prefix, name)));
        Set<Namespace> newNamespaces = new LinkedHashSet<>();
        newPrefixes.forEach((prefix, name) -> newNamespaces.add(new org.eclipse.rdf4j.model.impl.SimpleNamespace(prefix, name)));
        recordPrefixChanges(actor, oldNamespaces, newNamespaces);
    }

    private record PrefixChange(String opType, String prefix, String oldNamespace, String newNamespace) {}

    private void recordPrefixChanges(Actor actor, Set<Namespace> oldNamespaces, Set<Namespace> newNamespaces) {
        Map<String, String> oldPrefixes = new LinkedHashMap<>();
        oldNamespaces.forEach(ns -> oldPrefixes.put(ns.getPrefix(), ns.getName()));
        Map<String, String> newPrefixes = new LinkedHashMap<>();
        newNamespaces.forEach(ns -> newPrefixes.put(ns.getPrefix(), ns.getName()));

        List<PrefixChange> changes = new ArrayList<>();
        for (Map.Entry<String, String> entry : newPrefixes.entrySet()) {
            String oldNamespace = oldPrefixes.get(entry.getKey());
            if (oldNamespace == null) {
                changes.add(new PrefixChange("prefixAdded", entry.getKey(), null, entry.getValue()));
            } else if (!oldNamespace.equals(entry.getValue())) {
                changes.add(new PrefixChange("prefixModified", entry.getKey(), oldNamespace, entry.getValue()));
            }
        }
        for (String prefix : oldPrefixes.keySet()) {
            if (!newPrefixes.containsKey(prefix)) {
                changes.add(new PrefixChange("prefixDeleted", prefix, oldPrefixes.get(prefix), null));
            }
        }

        if (changes.isEmpty()) {
            return;
        }
        changes.forEach(c -> recordPrefixDraft(actor, c));
        if (changes.size() == 1) {
            PrefixChange c = changes.get(0);
            historyService.recordEdit(actor.projectId(), actor.userId(), actor.username(), c.opType(),
                    null, c.prefix(), c.oldNamespace(), c.newNamespace(),
                    describePrefixChange(c), null, null, actor.draft(), actor.origin());
            return;
        }

        String summary = changes.stream().map(this::describePrefixChange).collect(Collectors.joining("; "));
        List<Map<String, String>> parts = new ArrayList<>();
        for (PrefixChange c : changes) {
            Map<String, String> part = new LinkedHashMap<>();
            part.put("predicate", c.prefix());
            part.put("annotationProperty", c.opType());
            if (c.oldNamespace() != null) {
                part.put("oldValue", c.oldNamespace());
            }
            if (c.newNamespace() != null) {
                part.put("newValue", c.newNamespace());
            }
            part.put("addition", String.valueOf(c.newNamespace() != null));
            parts.add(part);
        }
        historyService.recordEdit(actor.projectId(), actor.userId(), actor.username(), PrefixReversal.AGGREGATE,
                null, changes.size() + " prefixes", null, null,
                "Code View save changed " + changes.size() + " prefixes (" + summary + ")",
                null, parts, actor.draft(), actor.origin());
    }

    private void recordPrefixDraft(Actor actor, PrefixChange c) {
        if (!actor.draft() || draftTrackingService == null) {
            return;
        }
        draftTrackingService.recordDescribed(actor.projectId(), actor.userId(), actor.username(),
                DraftRecords.prefixOperation(c.opType()), DraftRecords.prefix(c.prefix(), null, c.oldNamespace(),
                        c.newNamespace()), changeSets(actor));
    }

    private String describePrefixChange(PrefixChange c) {
        return switch (c.opType()) {
            case "prefixAdded" -> "added " + c.prefix() + ": <" + c.newNamespace() + ">";
            case "prefixDeleted" -> "removed " + c.prefix();
            default -> "changed " + c.prefix() + " from <" + c.oldNamespace() + "> to <" + c.newNamespace() + ">";
        };
    }

    private int groupBySubject(Set<Statement> added, Set<Statement> removed, Map<Resource, SubjectDiff> bySubject) {
        int structural = 0;
        for (Statement st : added) {
            if (!isNamedTriple(st)) { structural++; continue; }
            bySubject.computeIfAbsent(st.getSubject(), k -> new SubjectDiff(new ArrayList<>(), new ArrayList<>())).added().add(st);
        }
        for (Statement st : removed) {
            if (!isNamedTriple(st)) { structural++; continue; }
            bySubject.computeIfAbsent(st.getSubject(), k -> new SubjectDiff(new ArrayList<>(), new ArrayList<>())).removed().add(st);
        }
        return structural;
    }

    private void recordSubject(Actor actor, Resource subject, SubjectDiff diff, Model oldModel, Model newModel,
                               List<OntologyMutationService.MutationOp> draftOps) {
        TypeChange type = typeChange(diff);
        if (draftOps != null && (type.createOp() != null || type.deleteOp() != null)) {
            String op = type.createOp() != null ? type.createOp() : type.deleteOp();
            String label = findLabel(type.createOp() != null ? newModel : oldModel, subject);
            draftOps.add(OntologyMutationService.MutationOp.forTypeAssertion(op, subject.stringValue(), label));
        }

        List<Map<String, String>> subChanges = new ArrayList<>();
        for (Statement st : diff.added()) {
            if (!st.equals(type.createType())) subChanges.add(collectSubChange(st, true, newModel, draftOps));
        }
        for (Statement st : diff.removed()) {
            if (!st.equals(type.deleteType())) subChanges.add(collectSubChange(st, false, oldModel, draftOps));
        }

        String summary = subChanges.stream().map(this::describeSubChange).collect(Collectors.joining("; "));
        String opType;
        String label;
        String description;
        if (type.createOp() != null) {
            opType = type.createOp();
            label = findLabel(newModel, subject);
            description = opType + " operation via Code View" + (subChanges.isEmpty() ? "" : " (" + summary + ")");
        } else if (type.deleteOp() != null) {
            opType = type.deleteOp();
            label = findLabel(oldModel, subject);
            description = opType + " operation via Code View" + (subChanges.isEmpty() ? "" : " (also removed: " + summary + ")");
        } else if (!subChanges.isEmpty()) {
            opType = modificationOpType(subChanges.get(0));
            boolean header = isOntologyHeader(subject, oldModel, newModel);
            label = header ? headerLabel(subChanges) : findLabel(diff.added().isEmpty() ? oldModel : newModel, subject);
            description = header ? "Ontology header: " + summary
                    : "Code View save modified " + subChanges.size() + " propert"
                    + (subChanges.size() == 1 ? "y" : "ies") + " on this entity (" + summary + ")";
        } else {
            return;
        }
        historyService.recordEdit(actor.projectId(), actor.userId(), actor.username(), opType,
                subject.stringValue(), label, null, null, description, null, subChanges, actor.draft(), actor.origin());
    }

    private TypeChange typeChange(SubjectDiff diff) {
        String createOp = null;
        Statement createType = null;
        for (Statement st : diff.added()) {
            String op = typeDeclarationOp(st, true);
            if (op != null) { createOp = op; createType = st; break; }
        }
        String deleteOp = null;
        Statement deleteType = null;
        for (Statement st : diff.removed()) {
            String op = typeDeclarationOp(st, false);
            if (op != null) { deleteOp = op; deleteType = st; break; }
        }
        return new TypeChange(createOp, createType, deleteOp, deleteType);
    }

    private static String modificationOpType(Map<String, String> first) {
        boolean isAddition = "true".equals(first.get("addition"));
        return RDFS.SUBCLASSOF.stringValue().equals(first.get("predicate"))
                ? (isAddition ? "addSubClassOf" : "removeSubClassOf")
                : (isAddition ? "addStatement" : "removeStatement");
    }

    private String typeDeclarationOp(Statement st, boolean isAddition) {
        if (!st.getPredicate().equals(RDF.TYPE) || !(st.getObject() instanceof IRI typeIri)) {
            return null;
        }
        return switch (typeIri.stringValue()) {
            case "http://www.w3.org/2002/07/owl#Class" -> isAddition ? "createClass" : "deleteClass";
            case "http://www.w3.org/2002/07/owl#ObjectProperty" -> isAddition ? "createObjectProperty" : "deleteObjectProperty";
            case "http://www.w3.org/2002/07/owl#DatatypeProperty" -> isAddition ? "createDataProperty" : "deleteDataProperty";
            case "http://www.w3.org/2002/07/owl#AnnotationProperty" -> isAddition ? "createAnnotationProperty" : "deleteAnnotationProperty";
            case "http://www.w3.org/2000/01/rdf-schema#Datatype" -> isAddition ? "createDatatype" : "deleteDatatype";
            case "http://www.w3.org/2002/07/owl#NamedIndividual" -> isAddition ? "createIndividual" : "deleteIndividual";
            default -> null;
        };
    }

    private Map<String, String> collectSubChange(Statement st, boolean isAddition, Model context,
                                                 List<OntologyMutationService.MutationOp> draftOps) {
        String subjectIri = st.getSubject().stringValue();
        IRI predicate = st.getPredicate();
        String label = findLabel(context, st.getSubject());
        String value = st.getObject().stringValue();
        Map<String, String> subChange = new HashMap<>();
        subChange.put("predicate", predicate.stringValue());
        subChange.put("addition", String.valueOf(isAddition));
        subChange.put(isAddition ? "newValue" : "oldValue", value);

        if (predicate.equals(RDFS.SUBCLASSOF) && st.getObject() instanceof IRI) {
            if (draftOps != null) {
                String opType = isAddition ? "addSubClassOf" : "removeSubClassOf";
                draftOps.add(OntologyMutationService.MutationOp.forSubClassOfChange(opType, subjectIri, label, value, isAddition));
            }
            return subChange;
        }
        boolean annotation = predicate.equals(RDFS.LABEL) || predicate.equals(RDFS.COMMENT);
        if (annotation) {
            subChange.put("annotationProperty", predicate.stringValue());
        }
        if (draftOps != null) {
            String opType = annotation
                    ? (isAddition ? "addAnnotation" : "removeAnnotation")
                    : (isAddition ? "addStatement" : "removeStatement");
            draftOps.add(OntologyMutationService.MutationOp.forPropertyAssertion(opType, subjectIri, label,
                    predicate.stringValue(), value));
        }
        return subChange;
    }

    private static boolean isOntologyHeader(Resource subject, Model oldModel, Model newModel) {
        return newModel.contains(subject, RDF.TYPE, OWL.ONTOLOGY) || oldModel.contains(subject, RDF.TYPE, OWL.ONTOLOGY);
    }

    private String headerLabel(List<Map<String, String>> subChanges) {
        return subChanges.size() == 1 ? "ontology annotation " + extractLocalName(subChanges.get(0).get("predicate"))
                : "ontology header";
    }

    private String describeSubChange(Map<String, String> subChange) {
        String value = subChange.getOrDefault("newValue", subChange.get("oldValue"));
        return extractLocalName(subChange.get("predicate")) + (value != null ? ("=" + value) : "");
    }

    private boolean isNamedTriple(Statement st) {
        return st.getSubject() instanceof IRI
                && !(st.getObject() instanceof org.eclipse.rdf4j.model.BNode);
    }

    private String extractLocalName(String iri) {
        int idx = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return idx >= 0 && idx < iri.length() - 1 ? iri.substring(idx + 1) : iri;
    }

    private String findLabel(Model model, Resource subject) {
        return model.filter(subject, RDFS.LABEL, null).stream()
                .findFirst()
                .map(st -> st.getObject().stringValue())
                .orElseGet(() -> extractLocalName(subject.stringValue()));
    }
}
