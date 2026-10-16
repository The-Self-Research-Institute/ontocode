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

import java.util.Optional;

@Slf4j
@Component
public class RollbackGraphFacts {

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

    public boolean deltaHolds(String projectId, HistoryChange entry, boolean undo) {
        if (!entry.isDraft() && inMemoryIsAuthoritative(projectId)) {
            return deltaHoldsInMemory(projectId, entry, undo);
        }
        boolean draft = entry.isDraft() && entry.getUserId() != null && !entry.getUserId().isBlank();
        String graph = draft ? datasetService.getDraftGraphUri(projectId, entry.getUserId())
                : datasetService.getGraphUri(projectId);
        for (ExactDelta whole : ExactReversal.deltaOf(entry).stream().toList()) {
            for (ExactDelta delta : DeltaBatches.split(whole, Limit.DELTA_BATCH_TRIPLES.value())) {
                for (String query : DeltaSparql.holds(delta, undo, graph)) {
                    if (!datasetService.execAskInGraph(projectId, graph, query)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean deltaHoldsInMemory(String projectId, HistoryChange entry, boolean undo) {
        Optional<Model> snapshot = OwlApiModelSnapshot.of(ontologyCache, projectId);
        if (snapshot.isEmpty()) {
            return true;
        }
        SailRepository repository = new SailRepository(new MemoryStore());
        repository.init();
        try (RepositoryConnection connection = repository.getConnection()) {
            org.eclipse.rdf4j.model.IRI graph = SimpleValueFactory.getInstance().createIRI(SNAPSHOT_GRAPH);
            connection.add(snapshot.get(), graph);
            for (ExactDelta whole : ExactReversal.deltaOf(entry).stream().toList()) {
                for (ExactDelta delta : DeltaBatches.split(whole, Limit.DELTA_BATCH_TRIPLES.value())) {
                    for (String query : DeltaSparql.holds(delta, undo, SNAPSHOT_GRAPH)) {
                        if (!connection.prepareBooleanQuery(query).evaluate()) {
                            return false;
                        }
                    }
                }
            }
            return true;
        } finally {
            repository.shutDown();
        }
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
