package self.research.ontology.owlEditor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.Values;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OntologyHistoryService {

    private final SparqlDatasetService datasetService;

    @Autowired
    @Lazy
    private HistorySyncService historySyncService;

    @Autowired
    @Lazy
    private OntologyHistoryService self;

    @Autowired
    @Lazy
    private ExactCoverService exactCoverService;

    public java.util.List<String> draftChangeSetIds(String projectId, String userId) {
        return historySyncService == null ? java.util.List.of() : historySyncService.draftChangeSetIds(projectId, userId);
    }

    public void tagPrPublishedRows(String projectId, java.util.List<String> changeSetIds, String prId,
                                   String authorId, java.time.LocalDateTime since) {
        if (historySyncService != null) {
            historySyncService.tagRowsOfPr(projectId, changeSetIds, prId);
            historySyncService.tagUnlinkedRows(projectId, authorId, since,
                    java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(5), prId);
        }
    }

    public void markChangeSetsPublished(String projectId, java.util.List<String> changeSetIds) {
        if (historySyncService != null) {
            historySyncService.markChangeSetsPublished(projectId, changeSetIds);
        }
    }

    public void markDraftHistoryDiscarded(String projectId, String userId, java.util.Collection<String> keepChangeSetIds) {
        if (historySyncService != null) {
            historySyncService.markDraftsDiscarded(projectId, userId, keepChangeSetIds);
        }
    }

    public void markDraftHistoryDiscarded(String projectId, String userId) {
        if (historySyncService != null) {
            historySyncService.markDraftsDiscarded(projectId, userId);
        }
    }

    public Set<String> markDraftHistoryPublished(String projectId, String userId) {
        if (historySyncService == null) {
            return Set.of();
        }
        Set<String> iris = historySyncService.draftEntityIris(projectId, userId);
        historySyncService.markDraftsPublished(projectId, userId);
        return iris;
    }

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    private static final String HISTORY_NS = "http://ontology.research/history#";
    private static final IRI EDIT_OPERATION = vf.createIRI(HISTORY_NS, "EditOperation");
    private static final IRI HAS_USER_ID = vf.createIRI(HISTORY_NS, "hasUserId");
    private static final IRI HAS_USERNAME = vf.createIRI(HISTORY_NS, "hasUsername");
    private static final IRI HAS_TIMESTAMP = vf.createIRI(HISTORY_NS, "hasTimestamp");
    private static final IRI HAS_OPERATION_TYPE = vf.createIRI(HISTORY_NS, "hasOperationType");
    private static final IRI HAS_ENTITY_IRI = vf.createIRI(HISTORY_NS, "hasEntityIRI");
    private static final IRI HAS_ENTITY_LABEL = vf.createIRI(HISTORY_NS, "hasEntityLabel");
    private static final IRI HAS_OLD_VALUE = vf.createIRI(HISTORY_NS, "hasOldValue");
    private static final IRI HAS_NEW_VALUE = vf.createIRI(HISTORY_NS, "hasNewValue");
    private static final IRI HAS_DESCRIPTION = vf.createIRI(HISTORY_NS, "hasDescription");

    public void recordEdit(String projectId, String userId, String username,
                          String operationType, String entityIRI, String entityLabel,
                          String oldValue, String newValue, String description) {
        recordEdit(projectId, userId, username, operationType, entityIRI, entityLabel,
                   oldValue, newValue, description, null);
    }

    public void recordEdit(String projectId, String userId, String username,
                          String operationType, String entityIRI, String entityLabel,
                          String oldValue, String newValue, String description, String annotationProperty) {
        recordEdit(projectId, userId, username, operationType, entityIRI, entityLabel,
                   oldValue, newValue, description, annotationProperty, null);
    }

    public void recordEdit(String projectId, String userId, String username,
                          String operationType, String entityIRI, String entityLabel,
                          String oldValue, String newValue, String description, String annotationProperty,
                          List<Map<String, String>> subChanges) {
        recordEdit(projectId, userId, username, operationType, entityIRI, entityLabel,
                   oldValue, newValue, description, annotationProperty, subChanges, false);
    }

    public void recordEdit(String projectId, String userId, String username,
                          String operationType, String entityIRI, String entityLabel,
                          String oldValue, String newValue, String description, String annotationProperty,
                          List<Map<String, String>> subChanges, boolean draft) {
        recordEdit(projectId, userId, username, operationType, entityIRI, entityLabel,
                oldValue, newValue, description, annotationProperty, subChanges, draft, null);
    }

    public void recordEdit(String projectId, String userId, String username,
                          String operationType, String entityIRI, String entityLabel,
                          String oldValue, String newValue, String description, String annotationProperty,
                          List<Map<String, String>> subChanges, boolean draft, ChangeOrigin origin) {

        IRI historyGraph = vf.createIRI(HISTORY_NS + "graph/" + projectId);
        String editId = UUID.randomUUID().toString();
        IRI editIRI = vf.createIRI(HISTORY_NS + "edit/" + editId);
        long timestamp = System.currentTimeMillis();

        try {
            Map<String, Object> changeData = new HashMap<>();
            changeData.put("userId", userId);
            changeData.put("username", username);
            changeData.put("operationType", operationType);
            changeData.put("timestamp", timestamp);

            if (entityIRI != null) changeData.put("entityIRI", entityIRI);
            if (entityLabel != null) changeData.put("entityLabel", entityLabel);
            if (oldValue != null) changeData.put("oldValue", oldValue);
            if (newValue != null) changeData.put("newValue", newValue);
            if (description != null) changeData.put("description", description);
            if (annotationProperty != null) changeData.put("annotationProperty", annotationProperty);
            if (subChanges != null && !subChanges.isEmpty()) changeData.put("subChanges", subChanges);
            changeData.put("draft", draft);
            if (origin != null) {
                changeData.put("changeSetId", origin.changeSetId());
                changeData.put("source", origin.source());
                if (origin.ai() != null) changeData.put("ai", origin.ai());
                if (origin.revertsChangeSetId() != null) changeData.put("revertsChangeSetId", origin.revertsChangeSetId());
                if (origin.rollbackAuditId() != null) changeData.put("rollbackAuditId", origin.rollbackAuditId());
                if (origin.exact() != null) origin.exact().stamp(changeData, editId);
            }

            String entityType = determineEntityType(operationType);
            changeData.put("entityType", entityType);

            historySyncService.syncChange(projectId, editId, changeData);
            if (origin != null && origin.exact() != null) exactCoverService.record(projectId, origin.exact());
        } catch (Exception e) {
            log.error("[OntologyHistory] Failed to sync change to MongoDB", e);
        }

        self.mirrorEditToTripleStoreAsync(historyGraph, editIRI, editId, userId, username,
                operationType, entityIRI, entityLabel, oldValue, newValue, description,
                annotationProperty, timestamp);
    }

    @Async("metadataExecutor")
    public void mirrorEditToTripleStoreAsync(IRI historyGraph, IRI editIRI, String editId,
                          String userId, String username, String operationType,
                          String entityIRI, String entityLabel, String oldValue,
                          String newValue, String description, String annotationProperty,
                          long timestamp) {
        try (RepositoryConnection conn = datasetService.getRepository().getConnection()) {
            conn.begin();

            conn.add(editIRI, org.eclipse.rdf4j.model.vocabulary.RDF.TYPE, EDIT_OPERATION, historyGraph);
            conn.add(editIRI, HAS_USER_ID, vf.createLiteral(userId), historyGraph);
            conn.add(editIRI, HAS_USERNAME, vf.createLiteral(username), historyGraph);
            conn.add(editIRI, HAS_TIMESTAMP, vf.createLiteral(timestamp), historyGraph);
            conn.add(editIRI, HAS_OPERATION_TYPE, vf.createLiteral(operationType), historyGraph);

            if (entityIRI != null) {
                conn.add(editIRI, HAS_ENTITY_IRI, vf.createLiteral(entityIRI), historyGraph);
            }
            if (entityLabel != null) {
                conn.add(editIRI, HAS_ENTITY_LABEL, vf.createLiteral(entityLabel), historyGraph);
            }
            if (oldValue != null) {
                conn.add(editIRI, HAS_OLD_VALUE, vf.createLiteral(oldValue), historyGraph);
            }
            if (newValue != null) {
                conn.add(editIRI, HAS_NEW_VALUE, vf.createLiteral(newValue), historyGraph);
            }
            if (description != null) {
                conn.add(editIRI, HAS_DESCRIPTION, vf.createLiteral(description), historyGraph);
            }
            if (annotationProperty != null) {
                conn.add(editIRI, vf.createIRI(HISTORY_NS + "hasAnnotationProperty"),
                        vf.createLiteral(annotationProperty), historyGraph);
            }

            conn.commit();
            log.debug("[OntologyHistory] Recorded edit: {} by {} on {}", operationType, username, entityIRI);
        } catch (Exception e) {
            log.warn("[OntologyHistory] Failed to record edit in triple store (change is saved in MongoDB): {}",
                    e.getMessage());
        }
    }

    private static final String RDFS_SUBCLASSOF = "http://www.w3.org/2000/01/rdf-schema#subClassOf";
    private static final String RDFS_SUBPROPERTYOF = "http://www.w3.org/2000/01/rdf-schema#subPropertyOf";
    private static final String RDFS_DOMAIN = "http://www.w3.org/2000/01/rdf-schema#domain";
    private static final String RDFS_RANGE = "http://www.w3.org/2000/01/rdf-schema#range";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String OWL_DISJOINT_WITH = "http://www.w3.org/2002/07/owl#disjointWith";

    private static final java.util.Set<String> ENTITY_LEVEL_OP_TYPES = java.util.Set.of(
            "createClass", "deleteClass",
            "createObjectProperty", "deleteObjectProperty",
            "createDataProperty", "deleteDataProperty",
            "createAnnotationProperty", "deleteAnnotationProperty",
            "createDatatype", "deleteDatatype",
            "createIndividual", "deleteIndividual");

    public String recordGroupedMutations(String projectId, String userId, String username,
                                        List<self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp> ops,
                                        boolean draft) {
        if (ops == null || ops.isEmpty()) { return null; }
        ChangeOrigin origin = ChangeOrigin.manual();
        Map<String, List<self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp>> byIri = new LinkedHashMap<>();
        for (self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp op : ops) {
            if (op.iri() == null) {
                continue;
            }
            byIri.computeIfAbsent(op.iri(), k -> new ArrayList<>()).add(op);
        }

        for (Map.Entry<String, List<self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp>> entry : byIri.entrySet()) {
            String iri = entry.getKey();
            List<self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp> group = entry.getValue();

            self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp primary = null;
            for (self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp op : group) {
                if (ENTITY_LEVEL_OP_TYPES.contains(op.type())) {
                    primary = op;
                    break;
                }
            }

            List<Map<String, String>> subChanges = new ArrayList<>();
            List<self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp> unsupported = new ArrayList<>();
            for (self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp op : group) {
                if (op == primary) {
                    continue;
                }
                List<Map<String, String>> scs = mutationOpToSubChanges(op);
                if (!scs.isEmpty()) {
                    subChanges.addAll(scs);
                } else {
                    unsupported.add(op);
                }
            }

            HistoryCreationParent.addTo(primary, subChanges);

            String primaryOpType;
            String label;
            String description;
            if (primary != null) {
                primaryOpType = primary.type();
                label = primary.label();
                description = primaryOpType + " operation"
                        + (subChanges.isEmpty() ? "" : " (" + describeSubChanges(subChanges) + ")");
            } else if (!subChanges.isEmpty()) {
                Map<String, String> first = subChanges.get(0);
                boolean firstIsAddition = "true".equals(first.get("addition"));
                boolean firstIsUpdate = subChanges.stream().anyMatch(sc -> "true".equals(sc.get("addition")) && subChanges.stream()
                        .anyMatch(other -> "false".equals(other.get("addition")) && java.util.Objects.equals(other.get("predicate"), sc.get("predicate"))));
                primaryOpType = RDFS_SUBCLASSOF.equals(first.get("predicate"))
                        ? (firstIsUpdate ? "updateSubClassOf" : firstIsAddition ? "addSubClassOf" : "removeSubClassOf")
                        : (firstIsUpdate ? "updateStatement" : firstIsAddition ? "addStatement" : "removeStatement");
                label = group.get(0).label();
                description = "Modified " + subChanges.size() + " propert"
                        + (subChanges.size() == 1 ? "y" : "ies") + " (" + describeSubChanges(subChanges) + ")";
            } else {
                for (self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp op : unsupported) {
                    recordEdit(projectId, userId, username, op.type(), op.iri(), op.label(),
                            HistoryClassification.oldValue(op), HistoryClassification.newValue(op), op.type() + " operation", op.property(), null, draft, origin);
                }
                continue;
            }

            recordEdit(projectId, userId, username, primaryOpType, iri, label, null, null, description, null, subChanges, draft, origin);

            for (self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp op : unsupported) {
                recordEdit(projectId, userId, username, op.type(), op.iri(), op.label(),
                        HistoryClassification.oldValue(op), HistoryClassification.newValue(op), op.type() + " operation", op.property(), null, draft, origin);
            }
        }
        return origin.changeSetId();
    }

    private List<Map<String, String>> mutationOpToSubChanges(
            self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp op) {
        String type = op.type();
        if ("updateSubClassOf".equals(type) || "updateDisjointWith".equals(type)) {
            String predicate = "updateSubClassOf".equals(type) ? RDFS_SUBCLASSOF : OWL_DISJOINT_WITH;
            String category = determineEntityType(type);
            Map<String, String> removed = new HashMap<>();
            removed.put("predicate", predicate);
            removed.put("oldValue", op.value());
            removed.put("addition", "false");
            removed.put("category", category);
            Map<String, String> added = new HashMap<>();
            added.put("predicate", predicate);
            added.put("newValue", op.target());
            added.put("addition", "true");
            added.put("category", category);
            return List.of(removed, added);
        }
        Map<String, String> single = mutationOpToSubChange(op);
        return single == null ? List.of() : List.of(single);
    }

    private Map<String, String> mutationOpToSubChange(
            self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp op) {
        String type = op.type();
        if (type == null) {
            return null;
        }
        boolean addition = type.startsWith("add") || type.startsWith("create");
        Map<String, String> sc = new HashMap<>();
        switch (type) {
            case "addDisjointWith", "deleteDisjointWith" -> {
                sc.put("predicate", OWL_DISJOINT_WITH);
                sc.put(addition ? "newValue" : "oldValue", op.target());
            }
            case "addSubClassOf", "deleteSubClassOf" -> {
                sc.put("predicate", RDFS_SUBCLASSOF);
                sc.put(addition ? "newValue" : "oldValue", op.target());
            }
            case "addSubPropertyOf", "deleteSubPropertyOf" -> {
                sc.put("predicate", RDFS_SUBPROPERTYOF);
                sc.put(addition ? "newValue" : "oldValue", op.target());
            }
            case "addPropertyDomain", "deletePropertyDomain", "addPropertyRange", "deletePropertyRange" -> {
                String plain = op.target() != null && !op.target().isBlank() ? op.target() : op.value();
                boolean restriction = op.restrictionType() != null && !op.restrictionType().isBlank();
                if (restriction || plain == null || plain.isBlank()) {
                    return null;
                }
                sc.put("predicate", type.endsWith("Domain") ? RDFS_DOMAIN : RDFS_RANGE);
                sc.put(addition ? "newValue" : "oldValue", plain);
            }
            case "addClassAssertion", "removeClassAssertion" -> {
                sc.put("predicate", RDF_TYPE);
                sc.put(addition ? "newValue" : "oldValue", op.classIri());
            }
            case "addCharacteristic", "deleteCharacteristic" -> {
                sc.put("predicate", RDF_TYPE);
                sc.put(addition ? "newValue" : "oldValue", op.target());
            }
            case "addAnnotation", "deleteAnnotation" -> {
                sc.put("predicate", op.property());
                sc.put("annotationProperty", op.property());
                sc.put(addition ? "newValue" : "oldValue", op.value());
                putIfPresent(sc, "language", op.language());
                putIfPresent(sc, "datatype", op.datatype());
            }
            default -> {
                return null;
            }
        }
        sc.put("addition", String.valueOf(addition));
        sc.put("category", determineEntityType(type));
        return sc;
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String describeSubChanges(List<Map<String, String>> subChanges) {
        List<String> parts = new ArrayList<>();
        for (Map<String, String> sc : subChanges) {
            String predicate = sc.get("predicate");
            String localName = predicate != null && predicate.contains("#")
                    ? predicate.substring(predicate.lastIndexOf('#') + 1) : predicate;
            String value = sc.getOrDefault("newValue", sc.get("oldValue"));
            parts.add(localName + (value != null ? ("=" + value) : ""));
        }
        return String.join("; ", parts);
    }

    private String determineEntityType(String operationType) {
        return HistoryClassification.entityType(operationType);
    }

    public List<Map<String, Object>> getHistory(String projectId, int limit) {
        IRI historyGraph = vf.createIRI(HISTORY_NS + "graph/" + projectId);
        List<Map<String, Object>> results = new ArrayList<>();

        String queryString = """
            PREFIX hist: <%s>
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

            SELECT ?edit ?userId ?username ?timestamp ?operationType
                   ?entityIRI ?entityLabel ?oldValue ?newValue ?description
            WHERE {
                GRAPH <%s> {
                    ?edit rdf:type hist:EditOperation .
                    ?edit hist:hasUserId ?userId .
                    ?edit hist:hasUsername ?username .
                    ?edit hist:hasTimestamp ?timestamp .
                    ?edit hist:hasOperationType ?operationType .
                    OPTIONAL { ?edit hist:hasEntityIRI ?entityIRI }
                    OPTIONAL { ?edit hist:hasEntityLabel ?entityLabel }
                    OPTIONAL { ?edit hist:hasOldValue ?oldValue }
                    OPTIONAL { ?edit hist:hasNewValue ?newValue }
                    OPTIONAL { ?edit hist:hasDescription ?description }
                }
            }
            ORDER BY DESC(?timestamp)
            LIMIT %d
            """.formatted(HISTORY_NS, historyGraph.toString(), limit);

        try (RepositoryConnection conn = datasetService.getRepository().getConnection()) {
            TupleQuery query = conn.prepareTupleQuery(queryString);

            try (TupleQueryResult queryResult = query.evaluate()) {
                while (queryResult.hasNext()) {
                    BindingSet bindings = queryResult.next();

                    Map<String, Object> edit = new HashMap<>();

                    String editIRI = bindings.getValue("edit").stringValue();
                    edit.put("id", editIRI);

                    edit.put("projectId", projectId);

                    edit.put("userId", bindings.getValue("userId").stringValue());
                    edit.put("username", bindings.getValue("username").stringValue());

                    long timestamp = Long.parseLong(bindings.getValue("timestamp").stringValue());
                    edit.put("timestamp", timestamp);

                    String operationType = bindings.getValue("operationType").stringValue();
                    edit.put("changeType", operationType);

                    String category = "OTHER";
                    if (operationType.contains("Class") || operationType.contains("CLASS")) {
                        category = "CLASS";
                    } else if (operationType.contains("Property") || operationType.contains("PROPERTY")) {
                        category = "PROPERTY";
                    } else if (operationType.contains("Individual") || operationType.contains("INDIVIDUAL")) {
                        category = "INDIVIDUAL";
                    } else if (operationType.contains("Annotation") || operationType.contains("ANNOTATION")) {
                        category = "ANNOTATION";
                    }
                    edit.put("changeCategory", category);

                    if (bindings.hasBinding("entityIRI")) {
                        edit.put("entityIRI", bindings.getValue("entityIRI").stringValue());
                    }

                    if (bindings.hasBinding("entityLabel")) {
                        edit.put("entityLabel", bindings.getValue("entityLabel").stringValue());
                    }

                    if (bindings.hasBinding("oldValue")) {
                        edit.put("oldValue", bindings.getValue("oldValue").stringValue());
                    }

                    if (bindings.hasBinding("newValue")) {
                        edit.put("newValue", bindings.getValue("newValue").stringValue());
                    }

                    if (bindings.hasBinding("description")) {
                        edit.put("description", bindings.getValue("description").stringValue());
                    }

                    edit.put("reverted", false);

                    results.add(edit);
                }
            }

            log.info("[OntologyHistory] Retrieved {} edit operations from history", results.size());
        } catch (Exception e) {
            log.error("[OntologyHistory] Failed to retrieve history", e);
        }

        return results;
    }

    public void clearHistory(String projectId) {
        IRI historyGraph = vf.createIRI(HISTORY_NS + "graph/" + projectId);

        try (RepositoryConnection conn = datasetService.getRepository().getConnection()) {
            conn.begin();
            conn.clear(historyGraph);
            conn.commit();
            log.info("[OntologyHistory] Cleared history for project: {}", projectId);
        } catch (Exception e) {
            log.error("[OntologyHistory] Failed to clear history", e);
        }
    }
}
