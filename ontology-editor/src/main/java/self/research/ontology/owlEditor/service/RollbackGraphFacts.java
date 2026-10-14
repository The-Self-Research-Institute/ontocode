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

    public boolean referencedByOthers(String projectId, String iri, boolean draft, String draftOwner) {
        if (iri == null || iri.isBlank() || (!draft && inMemoryIsAuthoritative(projectId))) {
            return false;
        }
        String safe = SparqlSafety.safeIri(iri);
        return ask(projectId, "ASK { ?s ?p <" + safe + "> FILTER(?s != <" + safe + ">) }", draft, draftOwner);
    }

    public boolean deltaHolds(String projectId, HistoryChange entry, boolean undo) {
        if (!entry.isDraft() && inMemoryIsAuthoritative(projectId)) {
            return deltaHoldsInMemory(projectId, entry, undo);
        }
        boolean draft = entry.isDraft() && entry.getUserId() != null && !entry.getUserId().isBlank();
        String graph = draft ? datasetService.getDraftGraphUri(projectId, entry.getUserId())
                : datasetService.getGraphUri(projectId);
        for (ExactDelta delta : ExactReversal.deltaOf(entry).stream().toList()) {
            for (String query : DeltaSparql.holds(delta, undo, graph)) {
                if (!datasetService.execAskInGraph(projectId, graph, query)) {
                    return false;
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
            for (ExactDelta delta : ExactReversal.deltaOf(entry).stream().toList()) {
                for (String query : DeltaSparql.holds(delta, undo, SNAPSHOT_GRAPH)) {
                    if (!connection.prepareBooleanQuery(query).evaluate()) {
                        return false;
                    }
                }
            }
            return true;
        } finally {
            repository.shutDown();
        }
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
