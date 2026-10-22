package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.delta.DeltaBatches;
import self.research.ontology.owlEditor.service.delta.DeltaSparql;
import self.research.ontology.owlEditor.service.delta.ExactDelta;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DraftMergeApplier {

    public static final String SESSION_PREFIX = "merge-draft-";
    public static final String SUMMARY = "mergeSummary";

    private static final Pattern SAFE_IRI = Pattern.compile("[^<>\"{}|^`\\\\\\s]+");

    private final OntologyMutationService mutations;
    private final DraftTrackingService draftTracking;
    private final OntologyHistoryService history;
    private final DraftCopyService draftCopies;
    private final ProjectWriteLockRegistry locks;

    private SparqlDatasetService datasetService;

    public DraftMergeApplier(OntologyMutationService mutations, DraftTrackingService draftTracking,
                             OntologyHistoryService history, DraftCopyService draftCopies,
                             ProjectWriteLockRegistry locks) {
        this.mutations = mutations;
        this.draftTracking = draftTracking;
        this.history = history;
        this.draftCopies = draftCopies;
        this.locks = locks;
    }

    @Autowired(required = false)
    void setDatasetService(SparqlDatasetService datasetService) {
        this.datasetService = datasetService;
    }

    public static boolean isMergeSession(String sessionId) {
        return sessionId != null && sessionId.startsWith(SESSION_PREFIX);
    }

    public void requireReady(String projectId, String userId) {
        if (!draftCopies.isReady(projectId, userId)) {
            throw new DraftNotReadyException();
        }
    }

    public String apply(String projectId, String userId, String username, ExactDelta delta, String summary) {
        requireReady(projectId, userId);
        String entity = OntologyMergeService.MERGE_IRI_PREFIX + UUID.randomUUID();
        ChangeOrigin origin = ChangeOrigin.manual().withExact(new ExactCarrier(delta));
        return locks.exclusive(projectId, () -> {
            for (ExactDelta batch : DeltaBatches.split(delta, Limit.DELTA_BATCH_TRIPLES.value())) {
                mutations.applyRawUpdate(projectId, DeltaSparql.forward(batch), true, userId);
            }
            history.recordEdit(projectId, userId, username, OntologyMergeService.MERGE_OPERATION, entity,
                    "Ontology merge", null, null, summary, null, null, true, origin);
            recordMarker(draftTracking, projectId, userId, username, entity, summarize(projectId, userId, delta, summary));
            return origin.changeSetId();
        });
    }

    Map<String, Object> summarize(String projectId, String userId, ExactDelta delta, String description) {
        return MergeDraftSummary.of(delta, description, iris -> lookup(projectId, userId, iris));
    }

    Map<String, Object> summarize(String projectId, HistoryChange entry) {
        return summaryOf(entry, iris -> lookup(projectId, entry.getUserId(), iris));
    }

    static Map<String, Object> summaryOf(HistoryChange entry, MergeDraftSummary.Lookup lookup) {
        try {
            ExactDelta delta = ExactDelta.of(entry.getExactDeleted(), entry.getExactInserted());
            return MergeDraftSummary.of(delta, entry.getDescription(), lookup);
        } catch (RuntimeException e) {
            log.warn("[MERGE] Could not summarise the redone merge {}: {}", entry.getEntityIRI(), e.getMessage());
            Map<String, Object> summary = new LinkedHashMap<>();
            if (entry.getDescription() != null) {
                summary.put("description", entry.getDescription());
            }
            return summary;
        }
    }

    private Map<String, MergeDraftSummary.Entity> lookup(String projectId, String userId, Collection<String> iris) {
        if (datasetService == null || userId == null || userId.isBlank()) {
            return Map.of();
        }
        String values = iris.stream().filter(iri -> SAFE_IRI.matcher(iri).matches())
                .map(iri -> "<" + iri + ">").collect(Collectors.joining(" "));
        if (values.isEmpty()) {
            return Map.of();
        }
        SparqlDatasetService.ProjectGraphTarget target = datasetService.graphTarget(projectId);
        String graph = datasetService.getDraftGraphUri(projectId, userId);
        String query = "SELECT ?s ?t ?l FROM <" + graph + "> WHERE { VALUES ?s { " + values + " } "
                + "OPTIONAL { ?s <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ?t } "
                + "OPTIONAL { ?s <http://www.w3.org/2000/01/rdf-schema#label> ?l } }";
        Map<String, String> kinds = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        try (RepositoryConnection connection = TolerantRdfParsing.apply(target.repository().getConnection());
             TupleQueryResult rows = connection.prepareTupleQuery(query).evaluate()) {
            while (rows.hasNext()) {
                BindingSet row = rows.next();
                String subject = row.getValue("s").stringValue();
                Value type = row.getValue("t");
                if (type instanceof IRI typeIri && MergeDraftSummary.kindOf(typeIri) != null) {
                    kinds.putIfAbsent(subject, MergeDraftSummary.kindOf(typeIri));
                }
                Value label = row.getValue("l");
                if (label != null) {
                    labels.putIfAbsent(subject, label.stringValue());
                }
            }
        } catch (RuntimeException e) {
            log.warn("[MERGE] Could not look up names for the merge summary in project {}: {}", projectId, e.getMessage());
            return Map.of();
        }
        Map<String, MergeDraftSummary.Entity> found = new LinkedHashMap<>();
        for (String iri : iris) {
            if (kinds.containsKey(iri) || labels.containsKey(iri)) {
                found.put(iri, new MergeDraftSummary.Entity(kinds.get(iri), labels.get(iri)));
            }
        }
        return found;
    }

    static void recordMarker(DraftTrackingService draftTracking, String projectId, String userId, String username,
                             String entity, Map<String, Object> summary) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", OntologyMergeService.MERGE_OPERATION);
        data.put("iri", entity);
        data.put("label", "Ontology merge");
        if (summary != null && summary.get("description") != null) {
            data.put("value", summary.get("description"));
        }
        if (summary != null && !summary.isEmpty()) {
            data.put(SUMMARY, summary);
        }
        draftTracking.recordDraft(projectId, userId, username, OntologyMergeService.MERGE_OPERATION, data,
                SESSION_PREFIX + UUID.randomUUID());
    }
}
