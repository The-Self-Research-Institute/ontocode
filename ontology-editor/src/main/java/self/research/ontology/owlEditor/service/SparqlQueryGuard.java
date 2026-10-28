package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.algebra.AggregateFunctionCall;
import org.eclipse.rdf4j.query.algebra.ArbitraryLengthPath;
import org.eclipse.rdf4j.query.algebra.FunctionCall;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.ZeroLengthPath;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.parser.ParsedBooleanQuery;
import org.eclipse.rdf4j.query.parser.ParsedDescribeQuery;
import org.eclipse.rdf4j.query.parser.ParsedGraphQuery;
import org.eclipse.rdf4j.query.parser.ParsedOperation;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.ParsedTupleQuery;
import org.eclipse.rdf4j.query.parser.ParsedUpdate;
import org.eclipse.rdf4j.query.parser.QueryParserUtil;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class SparqlQueryGuard {

    public enum Kind { SELECT, ASK, CONSTRUCT, DESCRIBE }

    public record Checked(ParsedQuery parsed, Kind kind, String text) {}

    private static final int MAX_PARSE_MESSAGE = 300;
    private static final Pattern PERCENT = Pattern.compile("%[0-9a-fA-F]{2}");
    private static final List<String> ALLOWED_FUNCTION_NAMESPACES = List.of(
            "http://www.w3.org/2001/XMLSchema#",
            "http://www.w3.org/2005/xpath-functions#",
            "http://www.w3.org/2005/xpath-functions/math#");

    private SparqlQueryGuard() {}

    public static Checked check(String text, Set<String> authorizedGraphs) {
        if (text == null || text.isBlank()) {
            throw new SparqlQueryRejectedException("Query must not be empty");
        }
        text = decodeUnicodeEscapes(text);
        ParsedOperation op;
        try {
            op = QueryParserUtil.parseOperation(QueryLanguage.SPARQL, text, null);
        } catch (MalformedQueryException | IllegalArgumentException e) {
            throw new SparqlQueryRejectedException("The query could not be parsed: " + firstLine(e.getMessage()));
        }
        if (op instanceof ParsedUpdate) {
            throw new SparqlQueryRejectedException(
                    "This is an UPDATE. The query endpoint only runs SELECT, ASK, CONSTRUCT and DESCRIBE; use the update tab for changes.");
        }
        if (!(op instanceof ParsedQuery parsed)) {
            throw new SparqlQueryRejectedException("Only SELECT, ASK, CONSTRUCT and DESCRIBE queries are supported.");
        }
        Kind kind;
        if (parsed instanceof ParsedTupleQuery) {
            kind = Kind.SELECT;
        } else if (parsed instanceof ParsedBooleanQuery) {
            kind = Kind.ASK;
        } else if (parsed instanceof ParsedDescribeQuery) {
            kind = Kind.DESCRIBE;
        } else if (parsed instanceof ParsedGraphQuery) {
            kind = Kind.CONSTRUCT;
        } else {
            throw new SparqlQueryRejectedException("Only SELECT, ASK, CONSTRUCT and DESCRIBE queries are supported.");
        }
        if (parsed.getDataset() != null) {
            throw new SparqlQueryRejectedException(
                    "FROM and FROM NAMED are not supported; queries run against the project you selected.");
        }
        Set<String> allowed = authorizedGraphs.stream().map(SparqlQueryGuard::normalizeIri).collect(Collectors.toSet());
        parsed.getTupleExpr().visit(new Visitor(allowed));
        return new Checked(parsed, kind, text);
    }

    public static String decodeUnicodeEscapes(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && (s.charAt(i + 1) == 'u' || s.charAt(i + 1) == 'U')) {
                int len = s.charAt(i + 1) == 'u' ? 4 : 8;
                if (i + 2 + len <= s.length()) {
                    try {
                        out.appendCodePoint(Integer.parseInt(s.substring(i + 2, i + 2 + len), 16));
                        i += 2 + len;
                        continue;
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    public static String normalizeIri(String iri) {
        if (iri == null) {
            return null;
        }
        String value = iri.strip();
        Matcher m = PERCENT.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, m.group().toUpperCase(Locale.ROOT));
        }
        m.appendTail(sb);
        value = sb.toString();
        try {
            URI uri = new URI(value);
            if (uri.isOpaque()) {
                return value;
            }
            URI n = uri.normalize();
            String scheme = n.getScheme() == null ? null : n.getScheme().toLowerCase(Locale.ROOT);
            String host = n.getRawAuthority() == null ? null : n.getRawAuthority().toLowerCase(Locale.ROOT);
            StringBuilder out = new StringBuilder();
            if (scheme != null) {
                out.append(scheme).append(':');
            }
            if (host != null) {
                out.append("//").append(host);
            }
            if (n.getRawPath() != null) {
                out.append(n.getRawPath());
            }
            if (n.getRawQuery() != null) {
                out.append('?').append(n.getRawQuery());
            }
            if (n.getRawFragment() != null) {
                out.append('#').append(n.getRawFragment());
            }
            return out.toString();
        } catch (Exception e) {
            return value;
        }
    }

    private static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "syntax error";
        }
        String line = message.strip().split("\\R", 2)[0];
        return line.length() > MAX_PARSE_MESSAGE ? line.substring(0, MAX_PARSE_MESSAGE) : line;
    }

    private static final List<String> EXTENSION_PREFIXES = List.of(
            "java:", "http://jena.apache.org/", "http://jena.hpl.hp.com/", "urn:x-arq:");

    private static boolean isExtensionIri(String iri) {
        String lower = iri.toLowerCase(Locale.ROOT);
        for (String prefix : EXTENSION_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean functionAllowed(String iri) {
        if (iri == null) {
            return false;
        }
        if (iri.indexOf(':') < 0) {
            return true;
        }
        for (String ns : ALLOWED_FUNCTION_NAMESPACES) {
            if (iri.startsWith(ns)) {
                return true;
            }
        }
        return false;
    }

    private static final class Visitor extends AbstractQueryModelVisitor<RuntimeException> {
        private final Set<String> allowedGraphs;

        Visitor(Set<String> allowedGraphs) {
            this.allowedGraphs = allowedGraphs;
        }

        private void checkGraph(Var contextVar) {
            if (contextVar != null && contextVar.hasValue()) {
                Value v = contextVar.getValue();
                String iri = v.stringValue();
                if (!v.isIRI() || !allowedGraphs.contains(normalizeIri(iri))) {
                    throw new SparqlQueryRejectedException("GRAPH <" + iri
                            + "> is not available; queries only see the project you selected and your own draft when it is open.");
                }
            }
        }

        @Override
        public void meet(StatementPattern node) {
            checkGraph(node.getContextVar());
            Var pred = node.getPredicateVar();
            if (pred != null && pred.hasValue() && pred.getValue().isIRI()
                    && isExtensionIri(pred.getValue().stringValue())) {
                throw new SparqlQueryRejectedException("Extension property functions are not supported.");
            }
            super.meet(node);
        }

        @Override
        public void meet(ArbitraryLengthPath node) {
            checkGraph(node.getContextVar());
            super.meet(node);
        }

        @Override
        public void meet(ZeroLengthPath node) {
            checkGraph(node.getContextVar());
            super.meet(node);
        }

        @Override
        public void meet(Service node) {
            throw new SparqlQueryRejectedException(
                    "SERVICE is not supported; queries cannot reach other endpoints.");
        }

        @Override
        public void meet(FunctionCall node) {
            if (!functionAllowed(node.getURI())) {
                throw new SparqlQueryRejectedException("The function <" + node.getURI() + "> is not supported.");
            }
            super.meet(node);
        }

        @Override
        public void meet(AggregateFunctionCall node) {
            if (!functionAllowed(node.getIRI())) {
                throw new SparqlQueryRejectedException("The aggregate function <" + node.getIRI() + "> is not supported.");
            }
            super.meet(node);
        }
    }
}
