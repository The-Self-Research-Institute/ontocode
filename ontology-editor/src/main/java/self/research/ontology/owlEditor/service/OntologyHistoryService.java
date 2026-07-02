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

        IRI historyGraph = vf.createIRI(HISTORY_NS + "graph/" + projectId);
        String editId = UUID.randomUUID().toString();
        IRI editIRI = vf.createIRI(HISTORY_NS + "edit/" + editId);
        long timestamp = System.currentTimeMillis();

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

            // Sync to MongoDB for collaboration features
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

                // Determine entity type from operation
                String entityType = determineEntityType(operationType);
                changeData.put("entityType", entityType);

                historySyncService.syncChange(projectId, editId, changeData);
            } catch (Exception e) {
                log.error("[OntologyHistory] Failed to sync change to MongoDB", e);
            }
        } catch (Exception e) {
            log.error("[OntologyHistory] Failed to record edit history", e);
        }
    }

    /**
     * Determine entity type from operation type.
     */
    private String determineEntityType(String operationType) {
        if (operationType == null) return "OTHER";

        String upper = operationType.toUpperCase();
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
