package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.query.BindingSet;

import java.util.List;
import java.util.Map;

public sealed interface SparqlUserQueryResult {

    record Select(List<String> vars, List<BindingSet> rows, boolean truncated) implements SparqlUserQueryResult {
        public Select(List<String> vars, List<BindingSet> rows) {
            this(vars, rows, false);
        }
    }

    record Ask(boolean value) implements SparqlUserQueryResult {}

    record Graph(List<Statement> statements, Map<String, String> namespaces, boolean truncated)
            implements SparqlUserQueryResult {}
}
