package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.algebra.ArbitraryLengthPath;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.ZeroLengthPath;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.ParsedTupleQuery;
import org.eclipse.rdf4j.query.parser.QueryParserUtil;

final class AssistantSparqlGuard {

    record Verdict(String errorCode, String message) {
        static final Verdict OK = new Verdict(null, null);

        boolean allowed() {
            return errorCode == null;
        }
    }

    private static final int MAX_PARSE_MESSAGE = 300;

    private AssistantSparqlGuard() {}

    static Verdict check(String query, int maxChars) {
        if (query == null || query.isBlank()) {
            return new Verdict("NOT_SELECT_ONLY", "Only SELECT queries are allowed");
        }
        if (query.length() > maxChars) {
            return new Verdict("QUERY_TOO_LONG", "Query is longer than " + maxChars + " characters");
        }
        ParsedQuery parsed;
        try {
            parsed = QueryParserUtil.parseQuery(QueryLanguage.SPARQL, query, null);
        } catch (MalformedQueryException | IllegalArgumentException e) {
            return isUpdate(query)
                    ? new Verdict("NOT_SELECT_ONLY", "Only SELECT queries are allowed")
                    : new Verdict("MALFORMED_QUERY", "The query could not be parsed: " + firstLine(e.getMessage()));
        }
        if (!(parsed instanceof ParsedTupleQuery)) {
            return new Verdict("NOT_SELECT_ONLY", "Only SELECT queries are allowed");
        }
        if (parsed.getDataset() != null) {
            return outOfScope();
        }
        ScopeVisitor visitor = new ScopeVisitor();
        parsed.getTupleExpr().visit(visitor);
        return visitor.escapes ? outOfScope() : Verdict.OK;
    }

    private static boolean isUpdate(String query) {
        try {
            QueryParserUtil.parseUpdate(QueryLanguage.SPARQL, query, null);
            return true;
        } catch (MalformedQueryException | IllegalArgumentException e) {
            return false;
        }
    }

    private static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "syntax error";
        }
        String line = message.strip().split("\\R", 2)[0];
        return line.length() > MAX_PARSE_MESSAGE ? line.substring(0, MAX_PARSE_MESSAGE) : line;
    }

    private static Verdict outOfScope() {
        return new Verdict("SCOPE_NOT_ALLOWED",
                "FROM, FROM NAMED, GRAPH and SERVICE are not allowed; queries run against the project graph only");
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
