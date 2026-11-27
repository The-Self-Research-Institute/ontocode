package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.semanticweb.owlapi.model.IRI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.delta.DeltaBatches;
import self.research.ontology.owlEditor.service.delta.DeltaSparql;
import self.research.ontology.owlEditor.service.delta.ExactDelta;
import self.research.ontology.owlEditor.service.delta.ExactReversal;
import self.research.ontology.owlEditor.util.SparqlSafety;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

@Slf4j
@Component
public class RollbackGraphFacts {

    private static final String SWRL_IMP = "http://www.w3.org/2003/11/swrl#Imp";
    private static final String SNAPSHOT_GRAPH = "urn:ontocode:in-memory-snapshot";

    private final SparqlDatasetService datasetService;
    private final ProjectOntologyCache ontologyCache;
    private final DesktopOwlApiMutationService desktopOwlApi;

    @Autowired
    public RollbackGraphFacts(SparqlDatasetService datasetService,
                              @Autowired(required = false) @Nullable ProjectOntologyCache ontologyCache,
                              @Autowired(required = false) @Nullable DesktopOwlApiMutationService desktopOwlApi) {
        this.datasetService = datasetService;
        this.ontologyCache = ontologyCache;
        this.desktopOwlApi = desktopOwlApi;
    }

    public boolean entityExists(String projectId, String iri, boolean draft, String draftOwner) {
        if (iri == null || iri.isBlank()) {
            return false;
        }
        if (!draft && inMemoryIsAuthoritative(projectId)) {
            return ontologyCache.get(projectId)
                    .map(cached -> cached.ontology().containsEntityInSignature(IRI.create(iri)))
                    .orElse(false);
        }
        return ask(projectId, "ASK { <" + SparqlSafety.safeIri(iri) + "> ?p ?o }", draft, draftOwner);
    }

    public Boolean statementPresent(String projectId, String subject, String predicate, String value,
                                    boolean draft, String draftOwner) {
        if (subject == null || predicate == null || value == null) {
            return null;
        }
        if (!draft && inMemoryIsAuthoritative(projectId)) {
            return null;
        }
        String query = "ASK { <" + SparqlSafety.safeIri(subject) + "> <" + SparqlSafety.safeIri(predicate)
                + "> ?o FILTER(STR(?o) = " + RollbackMutationPlanner.stringLiteral(value) + ") }";
        return ask(projectId, query, draft, draftOwner);
    }

    public boolean referencedOutside(String projectId, String iri, boolean draft, String draftOwner, Model ownInserted) {
        if (iri == null || iri.isBlank() || (!draft && inMemoryIsAuthoritative(projectId))) {
            return false;
        }
        String safe = SparqlSafety.safeIri(iri);
        String own = ownReferences(iri, ownInserted);
        if (own == null) {
            return ask(projectId, "ASK { ?s ?p <" + safe + "> FILTER(?s != <" + safe + ">) }", draft, draftOwner);
        }
        return ask(projectId, "ASK { ?s ?p <" + safe + "> FILTER(?s != <" + safe + ">)" + own + " }", draft, draftOwner);
    }

    private static String ownReferences(String iri, Model ownInserted) {
        if (ownInserted == null || ownInserted.isEmpty()) {
            return "";
        }
        StringBuilder clauses = new StringBuilder();
        int count = 0;
        boolean blankSubject = false;
        for (org.eclipse.rdf4j.model.Statement st : ownInserted) {
            if (!st.getObject().stringValue().equals(iri) || !st.getObject().isIRI()) {
                continue;
            }
            if (st.getSubject().isBNode()) {
                blankSubject = true;
            } else if (++count > Limit.WHOLE_GRAPH_MAX_TRIPLES.value() / 10) {
                return null;
            } else {
                clauses.append(" FILTER(!(?s = <").append(SparqlSafety.safeIri(st.getSubject().stringValue()))
                        .append("> && ?p = <").append(SparqlSafety.safeIri(st.getPredicate().stringValue())).append(">))");
            }
        }
        return blankSubject ? clauses + " FILTER(!isBlank(?s))" : clauses.toString();
    }

    public record BatchState(boolean edited, boolean original) {
    }

    private interface GraphAsk {
        boolean ask(String query);
    }

    public boolean deltaHolds(String projectId, HistoryChange entry, boolean undo) {
        return inGraph(projectId, entry, (graph, ask) -> {
            for (ExactDelta whole : ExactReversal.checkedDeltaOf(entry).stream().toList()) {
                for (ExactDelta delta : DeltaBatches.split(whole, Limit.DELTA_BATCH_TRIPLES.value())) {
                    if (!all(DeltaSparql.holds(delta, undo, graph), ask)) {
                        return false;
                    }
                }
            }
            return true;
        }).orElse(true);
    }

    public Optional<List<BatchState>> batchStates(String projectId, HistoryChange entry, List<ExactDelta> batches) {
        return inGraph(projectId, entry, (graph, ask) -> batches.stream()
                .map(batch -> new BatchState(all(DeltaSparql.holds(batch, true, graph), ask),
                        all(DeltaSparql.holds(batch, false, graph), ask)))
                .toList());
    }

    private static boolean all(List<String> queries, GraphAsk ask) {
        for (String query : queries) {
            if (!ask.ask(query)) {
                return false;
            }
        }
        return true;
    }

    private <T> Optional<T> inGraph(String projectId, HistoryChange entry, BiFunction<String, GraphAsk, T> work) {
        if (!entry.isDraft() && inMemoryIsAuthoritative(projectId)) {
            Optional<Model> snapshot = OwlApiModelSnapshot.of(ontologyCache, projectId);
            if (snapshot.isEmpty()) {
                return Optional.empty();
            }
            SailRepository repository = new SailRepository(new MemoryStore());
            repository.init();
            try (RepositoryConnection connection = repository.getConnection()) {
                connection.add(snapshot.get(), SimpleValueFactory.getInstance().createIRI(SNAPSHOT_GRAPH));
                return Optional.ofNullable(work.apply(SNAPSHOT_GRAPH, query -> connection.prepareBooleanQuery(query).evaluate()));
            } finally {
                repository.shutDown();
            }
        }
        boolean draft = entry.isDraft() && entry.getUserId() != null && !entry.getUserId().isBlank();
        String graph = draft ? datasetService.getDraftGraphUri(projectId, entry.getUserId())
                : datasetService.getGraphUri(projectId);
        return Optional.ofNullable(work.apply(graph, query -> datasetService.execAskInGraph(projectId, graph, query)));
    }

    public boolean laterUsesNewName(String projectId, HistoryChange entry) {
        if (!"renameEntity".equals(entry.getOperationType()) || !entry.carriesExactDelta() || entry.getEntityIRI() == null
                || (!entry.isDraft() && inMemoryIsAuthoritative(projectId))) {
            return false;
        }
        String name = entry.getEntityIRI();
        long recorded = ExactReversal.deltaOf(entry).stream().flatMap(delta -> delta.inserted().stream())
                .filter(statement -> name.equals(statement.getSubject().stringValue())
                        || name.equals(statement.getPredicate().stringValue())
                        || name.equals(statement.getObject().stringValue()))
                .count();
        String safe = SparqlSafety.safeIri(name);
        String query = "ASK { { SELECT (COUNT(*) AS ?n) WHERE { SELECT DISTINCT ?s ?p ?o WHERE { ?s ?p ?o "
                + "FILTER(?s = <" + safe + "> || ?p = <" + safe + "> || ?o = <" + safe + ">) } } } FILTER(?n > " + recorded + ") }";
        return ask(projectId, query, entry.isDraft(), entry.getUserId());
    }

    public boolean ruleNameInUse(String projectId, String name, boolean draft, String draftOwner) {
        String wanted = name.trim().toLowerCase(java.util.Locale.ROOT);
        boolean owned = draft && draftOwner != null && !draftOwner.isBlank();
        if (!owned && inMemoryIsAuthoritative(projectId)) {
            return OwlApiModelSnapshot.of(ontologyCache, projectId).map(model -> {
                org.eclipse.rdf4j.model.ValueFactory vf = SimpleValueFactory.getInstance();
                for (org.eclipse.rdf4j.model.Resource rule : model.filter(null, org.eclipse.rdf4j.model.vocabulary.RDF.TYPE,
                        vf.createIRI(SWRL_IMP)).subjects()) {
                    for (org.eclipse.rdf4j.model.Value label : model.filter(rule, org.eclipse.rdf4j.model.vocabulary.RDFS.LABEL, null).objects()) {
                        if (label.stringValue().trim().toLowerCase(java.util.Locale.ROOT).equals(wanted)) {
                            return true;
                        }
                    }
                }
                return false;
            }).orElse(false);
        }
        String query = "ASK { ?r a <" + SWRL_IMP + "> . ?r <http://www.w3.org/2000/01/rdf-schema#label> ?l "
                + "FILTER(LCASE(REPLACE(STR(?l), \"^\\\\s+|\\\\s+$\", \"\")) = "
                + RollbackMutationPlanner.stringLiteral(wanted) + ") }";
        return ask(projectId, query, draft, draftOwner);
    }

    public boolean storeRecordsExact(String projectId, boolean draft) {
        return true;
    }

    private boolean inMemoryIsAuthoritative(String projectId) {
        return desktopOwlApi != null && ontologyCache != null && desktopOwlApi.isAuthoritative(projectId);
    }

    private boolean ask(String projectId, String query, boolean draft, String draftOwner) {
        if (draft && draftOwner != null && !draftOwner.isBlank()) {
            return datasetService.execAskInGraph(projectId, datasetService.getDraftGraphUri(projectId, draftOwner), query);
        }
        return datasetService.execAsk(projectId, query);
    }
}
