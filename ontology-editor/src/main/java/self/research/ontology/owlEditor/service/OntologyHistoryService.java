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
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Service for storing and retrieving edit history in GraphDB.
 * History is stored as RDF triples in a separate named graph.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OntologyHistoryService {

    private final SparqlDatasetService datasetService;

    @Autowired
    @Lazy
    private HistorySyncService historySyncService;
    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    // History vocabulary
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

    /**
     * Record an edit operation to GraphDB history graph.
     */
    public void recordEdit(String projectId, String userId, String username,
                          String operationType, String entityIRI, String entityLabel,
                          String oldValue, String newValue, String description) {
        recordEdit(projectId, userId, username, operationType, entityIRI, entityLabel,
                   oldValue, newValue, description, null);
    }

    /**
     * Record an edit operation with annotation property
     */
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

        IRI historyGraph = vf.createIRI(HISTORY_NS + "graph/" + projectId);
        String editId = UUID.randomUUID().toString();
        IRI editIRI = vf.createIRI(HISTORY_NS + "edit/" + editId);
        long timestamp = System.currentTimeMillis();

        // Mongo first: history_changes powers the Change Assistant and collaboration
        // views, and must survive Fuseki being down (desktop defers Fuseki startup —
        // OWLAPI-first mode — so the RDF write below can fail with connection refused).
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

            // Determine entity type from operation
            String entityType = determineEntityType(operationType);
            changeData.put("entityType", entityType);

            historySyncService.syncChange(projectId, editId, changeData);
        } catch (Exception e) {
            log.error("[OntologyHistory] Failed to sync change to MongoDB", e);
        }

        // RDF history graph: best-effort mirror in the triple store.
        try (RepositoryConnection conn = datasetService.getRepository().getConnection()) {
            conn.begin();

            // Add edit operation as RDF triples
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
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    private static final java.util.Set<String> ENTITY_LEVEL_OP_TYPES = java.util.Set.of(
            "createClass", "deleteClass",
            "createObjectProperty", "deleteObjectProperty",
            "createDataProperty", "deleteDataProperty",
            "createAnnotationProperty", "deleteAnnotationProperty",
            "createDatatype", "deleteDatatype",
            "createIndividual", "deleteIndividual");

    public void recordGroupedMutations(String projectId, String userId, String username,
                                        List<self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp> ops,
                                        boolean draft) {
        if (ops == null || ops.isEmpty()) {
            return;
        }
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
                Map<String, String> sc = mutationOpToSubChange(op);
                if (sc != null) {
                    subChanges.add(sc);
                } else {
                    unsupported.add(op);
                }
            }

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
                primaryOpType = RDFS_SUBCLASSOF.equals(first.get("predicate"))
                        ? (firstIsAddition ? "addSubClassOf" : "removeSubClassOf")
                        : (firstIsAddition ? "addStatement" : "removeStatement");
                label = group.get(0).label();
                description = "Modified " + subChanges.size() + " propert"
                        + (subChanges.size() == 1 ? "y" : "ies") + " (" + describeSubChanges(subChanges) + ")";
            } else {
                for (self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp op : unsupported) {
                    recordEdit(projectId, userId, username, op.type(), op.iri(), op.label(),
                            op.oldValue(), op.value(), op.type() + " operation", op.property(), null, draft);
                }
                continue;
            }

            recordEdit(projectId, userId, username, primaryOpType, iri, label, null, null, description, null, subChanges, draft);

            for (self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp op : unsupported) {
                recordEdit(projectId, userId, username, op.type(), op.iri(), op.label(),
                        op.oldValue(), op.value(), op.type() + " operation", op.property(), null, draft);
            }
        }
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
            case "addSubClassOf", "deleteSubClassOf" -> {
                sc.put("predicate", RDFS_SUBCLASSOF);
                sc.put(addition ? "newValue" : "oldValue", op.target());
            }
            case "addSubPropertyOf", "deleteSubPropertyOf" -> {
                sc.put("predicate", RDFS_SUBPROPERTYOF);
                sc.put(addition ? "newValue" : "oldValue", op.target());
            }
            case "addClassAssertion", "removeClassAssertion" -> {
                sc.put("predicate", RDF_TYPE);
                sc.put(addition ? "newValue" : "oldValue", op.classIri());
            }
            case "addAnnotation", "deleteAnnotation" -> {
                sc.put("predicate", op.property());
                sc.put("annotationProperty", op.property());
                sc.put(addition ? "newValue" : "oldValue", op.value());
            }
            default -> {
                return null;
            }
        }
        sc.put("addition", String.valueOf(addition));
        return sc;
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

    /**
     * Determine entity type from operation type.
     */
    private String determineEntityType(String operationType) {
        if (operationType == null) return "OTHER";

        String upper = operationType.toUpperCase();
        if (upper.contains("DATATYPE")) return "DATATYPE";
        if (upper.contains("CLASS")) return "CLASS";
        if (upper.contains("PROPERTY")) return "PROPERTY";
        if (upper.contains("INDIVIDUAL")) return "INDIVIDUAL";
        if (upper.contains("ANNOTATION")) return "ANNOTATION";
        if (upper.contains("AXIOM")) return "AXIOM";

        return "OTHER";
    }

    /**
     * Retrieve edit history for a project from GraphDB.
     */
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

                    // Generate unique ID from edit IRI
                    String editIRI = bindings.getValue("edit").stringValue();
                    edit.put("id", editIRI);

                    // Add projectId
                    edit.put("projectId", projectId);

                    edit.put("userId", bindings.getValue("userId").stringValue());
                    edit.put("username", bindings.getValue("username").stringValue());

                    long timestamp = Long.parseLong(bindings.getValue("timestamp").stringValue());
                    edit.put("timestamp", timestamp); // Keep as number for JavaScript

                    String operationType = bindings.getValue("operationType").stringValue();
                    edit.put("changeType", operationType);

                    // Determine category from operation type
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

    /**
     * Clear history for a project.
     */
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
