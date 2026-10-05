package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.OntologyChange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DraftChangeHistoryRecorder {

    private static final Logger log = LoggerFactory.getLogger(DraftChangeHistoryRecorder.class);

    private final ChangeTrackingService changeTrackingService;
    private final OntologyHistoryService historyService;

    public DraftChangeHistoryRecorder(ChangeTrackingService changeTrackingService,
                                      OntologyHistoryService historyService) {
        this.changeTrackingService = changeTrackingService;
        this.historyService = historyService;
    }

    public void record(String projectId, List<DraftChange> drafts) {
        try {
            drafts.forEach(draft -> recordTrackedChange(projectId, draft));
            Map<String, List<DraftChange>> byEntity = groupByEntity(drafts);
            byEntity.values().forEach(group -> recordBundledHistory(projectId, group));
            log.info("[DRAFT] Recorded {} changes to change tracking and GraphDB history ({} bundled entries)",
                    drafts.size(), byEntity.size());
        } catch (Exception e) {
            log.error("[DRAFT] Failed to record changes to change tracking", e);
        }
    }

    private void recordTrackedChange(String projectId, DraftChange draft) {
        OntologyChange.ChangeType changeType = mapOperationToChangeType(draft.getOperationType());
        if (changeType == null) {
            return;
        }
        Map<String, Object> data = draft.getOperationData();
        String entityIri = (String) data.get("iri");
        String label = (String) data.get("label");
        String oldValue = stringOrNull(data.get("oldValue"));
        String newValue = newValueOf(data);

        log.info("[DRAFT] Recording change - operationType: {}, oldValue: '{}', newValue: '{}', entityIRI: {}",
                draft.getOperationType(), oldValue, newValue, entityIri);
        log.info("[DRAFT] Operation data keys: {}", data.keySet());

        OntologyChange change = new OntologyChange.Builder(projectId, draft.getUserId(), draft.getUsername(), changeType)
                .changeCategory(determineCategory(draft.getOperationType()))
                .entityIRI(entityIri)
                .entityLabel(label != null ? label : entityIri)
                .description(formatChangeDescription(draft))
                .sessionId(draft.getSessionId())
                .oldValue(oldValue)
                .newValue(newValue)
                .build();
        changeTrackingService.recordChange(change);
    }

    private static Map<String, List<DraftChange>> groupByEntity(List<DraftChange> drafts) {
        Map<String, List<DraftChange>> byEntity = new LinkedHashMap<>();
        for (DraftChange draft : drafts) {
            Object iri = draft.getOperationData().get("iri");
            String key = iri != null ? iri.toString() : ("__no_entity__:" + draft.getId());
            byEntity.computeIfAbsent(key, k -> new ArrayList<>()).add(draft);
        }
        return byEntity;
    }

    private void recordBundledHistory(String projectId, List<DraftChange> group) {
        DraftChange primary = group.stream()
                .filter(d -> d.getOperationType() != null && d.getOperationType().startsWith("create"))
                .findFirst()
                .orElse(group.get(0));
        Map<String, Object> primaryData = primary.getOperationData();
        String entityIri = (String) primaryData.get("iri");
        String label = (String) primaryData.get("label");

        List<Map<String, String>> subChanges = new ArrayList<>();
        for (DraftChange draft : group) {
            if (draft != primary) {
                subChanges.add(toSubChangeMap(draft));
            }
        }
        String description = subChanges.isEmpty()
                ? formatChangeDescription(primary)
                : formatChangeDescription(primary) + " (" + subChanges.size() + " additional change"
                        + (subChanges.size() == 1 ? "" : "s") + ")";

        historyService.recordEdit(projectId, primary.getUserId(), primary.getUsername(), primary.getOperationType(),
                entityIri, label != null ? label : entityIri, stringOrNull(primaryData.get("oldValue")),
                newValueOf(primaryData), description, stringOrNull(primaryData.get("property")), subChanges, false);
    }

    private static String stringOrNull(Object value) {
        return value != null ? value.toString() : null;
    }

    private static String newValueOf(Map<String, Object> data) {
        return data.get("value") != null ? data.get("value").toString() : stringOrNull(data.get("newValue"));
    }

    private Map<String, String> toSubChangeMap(DraftChange draft) {
        Map<String, Object> data = draft.getOperationData();
        String opType = draft.getOperationType();
        boolean addition = opType == null || !(opType.startsWith("remove") || opType.startsWith("delete"));

        Map<String, String> subChange = new HashMap<>();
        subChange.put("addition", String.valueOf(addition));

        String predicate;
        if ("addSubClassOf".equals(opType) || "deleteSubClassOf".equals(opType)) {
            predicate = "http://www.w3.org/2000/01/rdf-schema#subClassOf";
            subChange.put(addition ? "newValue" : "oldValue", stringOrNull(data.get("parent")));
        } else if (data.get("property") != null) {
            predicate = data.get("property").toString();
            Object value = data.get("value") != null ? data.get("value") : data.get("newValue");
            String valueStr = stringOrNull(value);
            boolean looksLikeIri = valueStr != null && (valueStr.startsWith("http://") || valueStr.startsWith("https://"));
            if (!looksLikeIri) {
                subChange.put("annotationProperty", predicate);
            }
            subChange.put(addition ? "newValue" : "oldValue", valueStr);
        } else {
            predicate = opType;
            Object value = data.get("value") != null ? data.get("value") : data.get("newValue");
            subChange.put(addition ? "newValue" : "oldValue", stringOrNull(value));
        }
        subChange.put("predicate", predicate);
        return subChange;
    }

    private OntologyChange.ChangeType mapOperationToChangeType(String operationType) {
        return switch (operationType) {
            case "createClass" -> OntologyChange.ChangeType.ADD_CLASS;
            case "deleteClass" -> OntologyChange.ChangeType.REMOVE_CLASS;
            case "updateClassLabel" -> OntologyChange.ChangeType.RENAME_CLASS;
            case "createIndividual" -> OntologyChange.ChangeType.ADD_INDIVIDUAL;
            case "deleteIndividual" -> OntologyChange.ChangeType.REMOVE_INDIVIDUAL;
            case "createObjectProperty" -> OntologyChange.ChangeType.ADD_OBJECT_PROPERTY;
            case "deleteObjectProperty" -> OntologyChange.ChangeType.REMOVE_OBJECT_PROPERTY;
            case "createDataProperty" -> OntologyChange.ChangeType.ADD_DATA_PROPERTY;
            case "deleteDataProperty" -> OntologyChange.ChangeType.REMOVE_DATA_PROPERTY;
            case "addAnnotation" -> OntologyChange.ChangeType.ADD_ANNOTATION;
            case "updateAnnotation" -> OntologyChange.ChangeType.MODIFY_ANNOTATION;
            case "deleteAnnotation" -> OntologyChange.ChangeType.REMOVE_ANNOTATION;
            case "addSubClassOf" -> OntologyChange.ChangeType.ADD_SUBCLASS;
            case "deleteSubClassOf" -> OntologyChange.ChangeType.REMOVE_SUBCLASS;
            case "addEquivalentClass", "deleteEquivalentClass", "addDisjointWith", "deleteDisjointWith" -> OntologyChange.ChangeType.ADD_AXIOM;
            case "addPropertyDomain" -> OntologyChange.ChangeType.ADD_DOMAIN;
            case "deletePropertyDomain" -> OntologyChange.ChangeType.REMOVE_DOMAIN;
            case "addPropertyRange" -> OntologyChange.ChangeType.ADD_RANGE;
            case "deletePropertyRange" -> OntologyChange.ChangeType.REMOVE_RANGE;
            case "addSubPropertyOf", "deleteSubPropertyOf" -> OntologyChange.ChangeType.ADD_AXIOM;
            default -> OntologyChange.ChangeType.OTHER;
        };
    }

    private String determineCategory(String operationType) {
        if (operationType.contains("Class")) {
            return "CLASS";
        }
        if (operationType.contains("Individual")) {
            return "INDIVIDUAL";
        }
        if (operationType.contains("Property")) {
            return "PROPERTY";
        }
        if (operationType.contains("Annotation")) {
            return "ANNOTATION";
        }
        if (operationType.contains("Axiom")) {
            return "AXIOM";
        }
        return "OTHER";
    }

    private String formatChangeDescription(DraftChange draft) {
        Map<String, Object> data = draft.getOperationData();
        String label = (String) data.get("label");
        String iri = (String) data.get("iri");
        String displayName = label != null ? label : iri;

        return switch (draft.getOperationType()) {
            case "createClass" -> "Created class: " + displayName;
            case "deleteClass" -> "Deleted class: " + displayName;
            case "updateClassLabel" -> "Updated label: " + displayName;
            case "createIndividual" -> "Created individual: " + displayName;
            case "deleteIndividual" -> "Deleted individual: " + displayName;
            case "createObjectProperty" -> "Created property: " + displayName;
            case "deleteObjectProperty" -> "Deleted property: " + displayName;
            case "createDataProperty" -> "Created data property: " + displayName;
            case "deleteDataProperty" -> "Deleted data property: " + displayName;
            case "createAnnotationProperty" -> "Created annotation property: " + displayName;
            case "deleteAnnotationProperty" -> "Deleted annotation property: " + displayName;
            case "addAnnotation" -> "Added annotation to: " + displayName;
            case "updateAnnotation" -> "Updated annotation for: " + displayName;
            case "deleteAnnotation" -> "Removed annotation from: " + displayName;
            case "addSubClassOf" -> "Added subclass axiom for: " + displayName;
            case "deleteSubClassOf" -> "Removed subclass axiom from: " + displayName;
            case "addEquivalentClass" -> "Added equivalent class for: " + displayName;
            case "deleteEquivalentClass" -> "Removed equivalent class from: " + displayName;
            case "addDisjointWith" -> "Added disjoint axiom for: " + displayName;
            case "deleteDisjointWith" -> "Removed disjoint axiom from: " + displayName;
            default -> "Modified: " + displayName;
        };
    }
}
