package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.service.MainGraphRevisionService;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.SparqlQueryScope;
import self.research.ontology.owlEditor.service.StorageManager;

import java.util.ArrayList;
import java.util.List;

@Component
public class RepositoryInferenceSource implements InferenceSource {

    private final SparqlDatasetService datasetService;

    @Autowired(required = false)
    private StorageManager storageManager;

    @Autowired(required = false)
    private MainGraphRevisionService mainGraphRevisionService;

    public RepositoryInferenceSource(SparqlDatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @Override
    public String revision(SparqlQueryScope scope, String userId) {
        String projectId = scope.projectId();
        if (scope.draft()) {
            long version = storageManager != null ? storageManager.getDraftGraphVersion(projectId, userId) : 0L;
            return "draft:" + version + "." + datasetService.getDraftWriteStamp(projectId, userId);
        }
        long main = mainGraphRevisionService != null ? mainGraphRevisionService.getRevision(projectId) : -1L;
        long published = storageManager != null ? storageManager.getPublicGraphVersion(projectId) : -1L;
        return "main:" + main + "." + published;
    }

    @Override
    public List<Statement> read(SparqlQueryScope scope, int maxStatements) {
        IRI[] contexts = scope.graphs().stream().map(g -> SimpleValueFactory.getInstance().createIRI(g)).toArray(IRI[]::new);
        List<Statement> out = new ArrayList<>();
        try (RepositoryConnection conn = datasetService.getRepository(scope.projectId()).getConnection();
             RepositoryResult<Statement> result = conn.getStatements(null, null, null, false, contexts)) {
            while (result.hasNext()) {
                out.add(result.next());
                if (out.size() > maxStatements) {
                    throw new InferenceException(InferenceException.TOO_LARGE, "The ontology has more than " + maxStatements
                            + " triples, which is above the limit for inferred queries.");
                }
            }
        }
        return out;
    }
}
