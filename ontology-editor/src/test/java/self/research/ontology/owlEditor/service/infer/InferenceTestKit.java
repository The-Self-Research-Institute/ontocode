package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import self.research.ontology.owlEditor.service.SparqlQueryGuard;
import self.research.ontology.owlEditor.service.SparqlQueryScope;
import self.research.ontology.owlEditor.service.SparqlUserQueryResult;
import self.research.ontology.owlEditor.service.SparqlUserQueryRunner;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

final class InferenceTestKit {

    static final String P = "PREFIX : <http://e/> PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> PREFIX owl: <http://www.w3.org/2002/07/owl#> ";

    final InferenceFixtures.MutableSource source = new InferenceFixtures.MutableSource();
    final InferenceService service;

    InferenceTestKit() {
        this(10, 100_000, 200_000);
    }

    InferenceTestKit(int maxGroup, int maxEquality, int maxAsserted) {
        service = new InferenceService(source, null, new InferenceSnapshotCache(5, 1_800_000L, 10_000_000L, System::currentTimeMillis), maxGroup, maxEquality, maxAsserted);
    }

    boolean askInferred(SparqlQueryScope scope, String user, String body, boolean refresh) {
        InferenceService.Answer answer = service.query(scope, user, P + "ASK { " + body + " }", refresh, 1000);
        return ((SparqlUserQueryResult.Ask) answer.result()).value();
    }

    boolean askAsserted(SparqlQueryScope scope, String body) {
        try (RepositoryResource res = new RepositoryResource(source.read(scope, Integer.MAX_VALUE))) {
            SparqlQueryGuard.Checked checked = SparqlQueryGuard.check(P + "ASK { " + body + " }", new LinkedHashSet<>(scope.graphs()));
            try (RepositoryConnection c = res.repo.getConnection()) {
                return ((SparqlUserQueryResult.Ask) SparqlUserQueryRunner.run(c, checked, scope.graphs(), 1000)).value();
            }
        }
    }

    List<List<String>> rows(SparqlQueryScope scope, String user, String select, String... vars) {
        InferenceService.Answer answer = service.query(scope, user, P + select, true, 1000);
        List<List<String>> out = new ArrayList<>();
        for (BindingSet row : ((SparqlUserQueryResult.Select) answer.result()).rows()) {
            List<String> r = new ArrayList<>();
            for (String v : vars) {
                r.add(row.hasBinding(v) ? row.getValue(v).stringValue() : null);
            }
            out.add(r);
        }
        return out.stream().sorted((a, b) -> a.toString().compareTo(b.toString())).collect(Collectors.toList());
    }

    static final class RepositoryResource implements AutoCloseable {
        final Repository repo = new SailRepository(new MemoryStore());

        RepositoryResource(List<Statement> statements) {
            repo.init();
            try (RepositoryConnection c = repo.getConnection()) {
                c.add(statements);
            }
        }

        @Override
        public void close() {
            repo.shutDown();
        }
    }
}
