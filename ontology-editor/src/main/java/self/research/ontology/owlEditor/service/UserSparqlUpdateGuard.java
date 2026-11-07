package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.algebra.ArbitraryLengthPath;
import org.eclipse.rdf4j.query.algebra.DeleteData;
import org.eclipse.rdf4j.query.algebra.InsertData;
import org.eclipse.rdf4j.query.algebra.Modify;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.UpdateExpr;
import org.eclipse.rdf4j.query.algebra.ZeroLengthPath;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.ParsedUpdate;
import org.eclipse.rdf4j.query.parser.QueryParserUtil;
import self.research.ontology.owlEditor.util.SparqlText;

public final class UserSparqlUpdateGuard {

    public record Verdict(String message) {
        static final Verdict OK = new Verdict(null);

        public boolean allowed() {
            return message == null;
        }
    }

    private static final int MAX_CHARS = 1_000_000;
    private static final String ONLY_PROJECT_GRAPH =
            "Only INSERT and DELETE on the project's own data are allowed; GRAPH, WITH, USING, SERVICE, LOAD, CLEAR, DROP, "
                    + "CREATE, COPY, MOVE and ADD are not";

    private static final String QUERY_PROJECT_GRAPH =
            "FROM, FROM NAMED, GRAPH and SERVICE are not allowed; queries run against the project graph only";

    private UserSparqlUpdateGuard() {
    }

    public static Verdict check(String update) {
        if (update == null || update.isBlank()) {
            return new Verdict("The update must not be empty");
        }
        if (update.length() > MAX_CHARS) {
            return new Verdict("The update is longer than " + MAX_CHARS + " characters");
        }
        if (SparqlText.hasKeyword(update, "WITH") || SparqlText.hasKeyword(update, "USING")) {
            return new Verdict(ONLY_PROJECT_GRAPH);
        }
        ParsedUpdate parsed;
        try {
            parsed = QueryParserUtil.parseUpdate(QueryLanguage.SPARQL, update, null);
        } catch (MalformedQueryException | IllegalArgumentException e) {
            return new Verdict("The update could not be parsed");
        }
        if (parsed.getUpdateExprs().isEmpty()) {
            return new Verdict(ONLY_PROJECT_GRAPH);
        }
        for (Dataset dataset : parsed.getDatasetMapping().values()) {
            if (dataset != null && (!dataset.getDefaultGraphs().isEmpty() || !dataset.getNamedGraphs().isEmpty()
                    || dataset.getDefaultInsertGraph() != null || !dataset.getDefaultRemoveGraphs().isEmpty())) {
                return new Verdict(ONLY_PROJECT_GRAPH);
            }
        }
        for (UpdateExpr expr : parsed.getUpdateExprs()) {
            if (!staysInProjectGraph(expr)) {
                return new Verdict(ONLY_PROJECT_GRAPH);
            }
        }
        return Verdict.OK;
    }

    public static Verdict checkQuery(String query) {
        if (query == null || query.isBlank()) {
            return new Verdict("The query must not be empty");
        }
        if (query.length() > MAX_CHARS) {
            return new Verdict("The query is longer than " + MAX_CHARS + " characters");
        }
        ParsedQuery parsed;
        try {
            parsed = QueryParserUtil.parseQuery(QueryLanguage.SPARQL, query, null);
        } catch (MalformedQueryException | IllegalArgumentException e) {
            return new Verdict("The query could not be parsed");
        }
        if (parsed.getDataset() != null) {
            return new Verdict(QUERY_PROJECT_GRAPH);
        }
        ScopeVisitor visitor = new ScopeVisitor();
        parsed.getTupleExpr().visit(visitor);
        return visitor.escapes ? new Verdict(QUERY_PROJECT_GRAPH) : Verdict.OK;
    }

    private static boolean staysInProjectGraph(UpdateExpr expr) {
        if (expr instanceof InsertData data) {
            return !SparqlText.hasKeyword(data.getDataBlock(), "GRAPH");
        }
        if (expr instanceof DeleteData data) {
            return !SparqlText.hasKeyword(data.getDataBlock(), "GRAPH");
        }
        if (expr instanceof Modify modify) {
            ScopeVisitor visitor = new ScopeVisitor();
            for (var part : new org.eclipse.rdf4j.query.algebra.TupleExpr[]{
                    modify.getDeleteExpr(), modify.getInsertExpr(), modify.getWhereExpr()}) {
                if (part != null) {
                    part.visit(visitor);
                }
            }
            return !visitor.escapes;
        }
        return false;
    }

    private static final class ScopeVisitor extends AbstractQueryModelVisitor<RuntimeException> {
        boolean escapes;

        @Override
        public void meet(StatementPattern node) {
            if (node.getScope() == StatementPattern.Scope.NAMED_CONTEXTS || node.getContextVar() != null) {
                escapes = true;
            }
            super.meet(node);
        }

        @Override
        public void meet(Service node) {
            escapes = true;
        }

        @Override
        public void meet(ArbitraryLengthPath node) {
            if (node.getContextVar() != null) {
                escapes = true;
            }
            super.meet(node);
        }

        @Override
        public void meet(ZeroLengthPath node) {
            if (node.getContextVar() != null) {
                escapes = true;
            }
            super.meet(node);
        }
    }
}
