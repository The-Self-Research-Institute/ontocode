package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.Repository;
import self.research.ontology.owlEditor.service.SparqlQueryScope;

import java.time.Instant;

final class InferenceStoreBuilder {

    private InferenceStoreBuilder() {
    }

    static InferredSnapshot build(InferenceSnapshotKey key, SparqlQueryScope scope, InferenceCapture.Captured captured,
                                  Materialization materialization,
                                  SnapshotStoreFactory stores) {
        SimpleValueFactory vf = SimpleValueFactory.getInstance();
        IRI inferredGraph = vf.createIRI(scope.graphs().get(0));
        Resource[] graphs = scope.graphs().stream().map(vf::createIRI).toArray(Resource[]::new);
        Repository repository = stores.create(captured.statements().size() + (long) materialization.inferred().size());
        long asserted;
        long inferred = 0;
        try (RepositoryConnection conn = repository.getConnection()) {
            conn.begin();
            conn.add(captured.statements());
            conn.commit();
            asserted = conn.size();
            conn.begin();
            for (Statement st : materialization.inferred()) {
                if (!conn.hasStatement(st.getSubject(), st.getPredicate(), st.getObject(), false, graphs)) {
                    conn.add(st.getSubject(), st.getPredicate(), st.getObject(), inferredGraph);
                    inferred++;
                }
            }
            conn.commit();
        }
        return new InferredSnapshot(key, repository, scope.graphs(), asserted, inferred, Instant.now(), materialization.withoutStatements());
    }
}
