package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
final class CodeViewHistoryRecorder {

    private static final String DESKTOP_USER_ID = "desktop-user-local";
    private static final int BULK_DIFF_THRESHOLD = 60;

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

    private boolean isNamedTriple(Statement st) {
        return st.getSubject() instanceof IRI
                && !(st.getObject() instanceof org.eclipse.rdf4j.model.BNode);
    }

    private String extractLocalName(String iri) {
        int idx = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return idx >= 0 && idx < iri.length() - 1 ? iri.substring(idx + 1) : iri;
    }

    private String findLabel(Model model, org.eclipse.rdf4j.model.Resource subject) {
        return model.filter(subject, RDFS.LABEL, null).stream()
                .findFirst()
                .map(st -> st.getObject().stringValue())
                .orElseGet(() -> extractLocalName(subject.stringValue()));
    }

    private void recordNamedStatementChange(String projectId, String userId, String username,
                                             Statement st, boolean isAddition, Model context,
                                             List<OntologyMutationService.MutationOp> draftOps) {
        String subjectIri = st.getSubject().stringValue();
        IRI predicate = st.getPredicate();
        String label = findLabel(context, st.getSubject());

        if (predicate.equals(RDF.TYPE) && st.getObject() instanceof IRI typeIri) {
            String opType = switch (typeIri.stringValue()) {
                case "http://www.w3.org/2002/07/owl#Class" -> isAddition ? "createClass" : "deleteClass";
                case "http://www.w3.org/2002/07/owl#ObjectProperty" -> isAddition ? "createObjectProperty" : "deleteObjectProperty";
                case "http://www.w3.org/2002/07/owl#DatatypeProperty" -> isAddition ? "createDataProperty" : "deleteDataProperty";
                case "http://www.w3.org/2002/07/owl#AnnotationProperty" -> isAddition ? "createAnnotationProperty" : "deleteAnnotationProperty";
                case "http://www.w3.org/2000/01/rdf-schema#Datatype" -> isAddition ? "createDatatype" : "deleteDatatype";
                case "http://www.w3.org/2002/07/owl#NamedIndividual" -> isAddition ? "createIndividual" : "deleteIndividual";
                default -> null;
            };
            if (opType != null) {
                historyService.recordEdit(projectId, userId, username, opType,
                        subjectIri, label, null, null,
                        opType + " operation via Code View", null);
                if (draftOps != null) {
                    draftOps.add(OntologyMutationService.MutationOp.forTypeAssertion(opType, subjectIri, label));
                }
            }
            return;
        }

        if (predicate.equals(RDFS.SUBCLASSOF) && st.getObject() instanceof IRI parentIri) {
            String opType = isAddition ? "addSubClassOf" : "removeSubClassOf";
            historyService.recordEdit(projectId, userId, username,
                    opType, subjectIri, label,
                    isAddition ? null : parentIri.stringValue(),
                    isAddition ? parentIri.stringValue() : null,
                    "subClassOf changed via Code View", null);
            if (draftOps != null) {
                draftOps.add(OntologyMutationService.MutationOp
                        .forSubClassOfChange(opType, subjectIri, label, parentIri.stringValue(), isAddition));
            }
            return;
        }

        if (predicate.equals(RDFS.LABEL) || predicate.equals(RDFS.COMMENT)) {
            String opType = isAddition ? "addAnnotation" : "removeAnnotation";
            historyService.recordEdit(projectId, userId, username,
                    opType, subjectIri, label, null, st.getObject().stringValue(),
                    "Annotation changed via Code View", predicate.stringValue());
            if (draftOps != null) {
                draftOps.add(OntologyMutationService.MutationOp
                        .forPropertyAssertion(opType, subjectIri, label, predicate.stringValue(), st.getObject().stringValue()));
            }
            return;
        }

        String opType = isAddition ? "addStatement" : "removeStatement";
        historyService.recordEdit(projectId, userId, username,
                opType, subjectIri, label,
                isAddition ? null : st.getObject().stringValue(),
                isAddition ? st.getObject().stringValue() : null,
                "Property assertion changed via Code View (" + predicate.stringValue() + ")",
                predicate.stringValue());
        if (draftOps != null) {
            draftOps.add(OntologyMutationService.MutationOp
                    .forPropertyAssertion(opType, subjectIri, label, predicate.stringValue(), st.getObject().stringValue()));
        }
    }

    void record(String projectId, String userId, String username, Model oldModel, Model newModel, boolean draft) {
        Set<Statement> added = new LinkedHashSet<>(newModel);
        added.removeAll(oldModel);
        Set<Statement> removed = new LinkedHashSet<>(oldModel);
        removed.removeAll(newModel);

        int namedChangeCount = 0;
        for (Statement st : added) {
            if (isNamedTriple(st)) namedChangeCount++;
        }
        for (Statement st : removed) {
            if (isNamedTriple(st)) namedChangeCount++;
        }

        if (namedChangeCount > BULK_DIFF_THRESHOLD) {
            historyService.recordEdit(projectId, userId, username,
                    "bulkPopulation", null, null, null, null,
                    "Code View save added/changed " + namedChangeCount
                            + " statements — logged as a single bulk entry rather than one per statement",
                    null);
            log.info("[CODE-VIEW-SAVE] Skipped per-triple change logging for bulk save ({} named changes)", namedChangeCount);
            return;
        }

        List<OntologyMutationService.MutationOp> draftOps = draft ? new ArrayList<>() : null;

        int structuralChanges = 0;
        for (Statement st : added) {
            if (!isNamedTriple(st)) { structuralChanges++; continue; }
            recordNamedStatementChange(projectId, userId, username, st, true, newModel, draftOps);
        }
        for (Statement st : removed) {
            if (!isNamedTriple(st)) { structuralChanges++; continue; }
            recordNamedStatementChange(projectId, userId, username, st, false, oldModel, draftOps);
        }
        if (structuralChanges > 0) {
            historyService.recordEdit(projectId, userId, username,
                    "codeViewStructuralEdit", null, null, null, null,
                    "Code View save modified " + structuralChanges
                            + " structural axiom(s) (restrictions, unions, SWRL rules, disjoint-class lists, etc.)",
                    null);
        }

        if (draft && draftOps != null && !draftOps.isEmpty()) {
            String sessionId = UUID.randomUUID().toString();
            draftTrackingService.recordDrafts(projectId, userId, username, draftOps, sessionId);
        }
    }
}
