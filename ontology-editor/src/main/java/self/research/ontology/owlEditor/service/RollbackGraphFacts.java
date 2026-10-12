package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.semanticweb.owlapi.model.IRI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.util.SparqlSafety;

@Slf4j
@Component
public class RollbackGraphFacts {

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
