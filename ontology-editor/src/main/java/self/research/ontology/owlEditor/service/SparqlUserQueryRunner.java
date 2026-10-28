package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.GraphQueryResult;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.algebra.Extension;
import org.eclipse.rdf4j.query.algebra.ExtensionElem;
import org.eclipse.rdf4j.query.algebra.Projection;
import org.eclipse.rdf4j.query.algebra.ProjectionElem;
import org.eclipse.rdf4j.query.algebra.ValueConstant;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import self.research.ontology.owlEditor.util.SparqlText;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SparqlUserQueryRunner {

    public static final int DESCRIBE_RESOURCE_CAP = 500;
    public static final int DESCRIBE_BNODE_DEPTH = 3;
    private static final int DESCRIBE_BATCH = 25;
    private static final int QUERY_TIMEOUT_SECONDS = 300;

    private SparqlUserQueryRunner() {}

    public static SimpleDataset dataset(Collection<String> graphs) {
        SimpleValueFactory vf = SimpleValueFactory.getInstance();
        SimpleDataset dataset = new SimpleDataset();
        for (String g : graphs) {
            IRI iri = vf.createIRI(g);
            dataset.addDefaultGraph(iri);
            dataset.addNamedGraph(iri);
        }
        return dataset;
    }

    public static SparqlUserQueryResult run(RepositoryConnection conn, SparqlQueryGuard.Checked checked,
                                            Collection<String> graphs, int graphCap) {
        SimpleDataset dataset = dataset(graphs);
        switch (checked.kind()) {
            case SELECT: {
                TupleQuery q = conn.prepareTupleQuery(checked.text());
                q.setDataset(dataset);
                q.setIncludeInferred(false);
                q.setMaxExecutionTime(QUERY_TIMEOUT_SECONDS);
                return selectOf(q);
            }
            case ASK: {
                BooleanQuery q = conn.prepareBooleanQuery(checked.text());
                q.setDataset(dataset);
                q.setIncludeInferred(false);
                q.setMaxExecutionTime(QUERY_TIMEOUT_SECONDS);
                return new SparqlUserQueryResult.Ask(q.evaluate());
            }
            case CONSTRUCT:
                return constructOf(conn, checked.text(), dataset, graphCap);
            case DESCRIBE:
                return describe(conn, checked, graphs, dataset, graphCap);
            default:
                throw new SparqlQueryRejectedException("Unsupported query form.");
        }
    }

    private static SparqlUserQueryResult selectOf(TupleQuery q) {
        List<BindingSet> rows = new ArrayList<>();
        List<String> vars = new ArrayList<>();
        try (TupleQueryResult rs = q.evaluate()) {
            vars.addAll(rs.getBindingNames());
            while (rs.hasNext()) {
                rows.add(rs.next());
            }
        }
        return new SparqlUserQueryResult.Select(vars, rows);
    }

    private static SparqlUserQueryResult.Graph constructOf(RepositoryConnection conn, String text,
                                                           SimpleDataset dataset, int cap) {
        GraphQuery q = conn.prepareGraphQuery(text);
        q.setDataset(dataset);
        q.setIncludeInferred(false);
        q.setMaxExecutionTime(QUERY_TIMEOUT_SECONDS);
        Set<Statement> out = new LinkedHashSet<>();
        Map<String, String> namespaces = new LinkedHashMap<>();
        boolean truncated = false;
        try (GraphQueryResult rs = q.evaluate()) {
            namespaces.putAll(rs.getNamespaces());
            while (rs.hasNext()) {
                Statement st = rs.next();
                if (out.size() >= cap && !out.contains(st)) {
                    truncated = true;
                    break;
                }
                out.add(st);
            }
        }
        return new SparqlUserQueryResult.Graph(new ArrayList<>(out), namespaces, truncated);
    }

    private static SparqlUserQueryResult.Graph describe(RepositoryConnection conn, SparqlQueryGuard.Checked checked,
                                                        Collection<String> graphs, SimpleDataset dataset, int cap) {
        DescribeShape shape = new DescribeShape();
        checked.parsed().getTupleExpr().visit(shape);
        shape.resolve();
        Set<Resource> resources = new LinkedHashSet<>();
        resources.addAll(shape.constants);
        boolean truncated = false;
        List<String> variableNames = new ArrayList<>(shape.projected);
        variableNames.removeAll(shape.constantNames);
        if (!variableNames.isEmpty()) {
            String surrogate = surrogateSelect(checked.text());
            if (surrogate != null) {
                SparqlQueryGuard.Checked sel = SparqlQueryGuard.check(surrogate, new LinkedHashSet<>(graphs));
                if (sel.kind() != SparqlQueryGuard.Kind.SELECT) {
                    throw new SparqlQueryRejectedException("The DESCRIBE query could not be interpreted.");
                }
                TupleQuery q = conn.prepareTupleQuery(sel.text());
                q.setDataset(dataset);
                q.setIncludeInferred(false);
                q.setMaxExecutionTime(QUERY_TIMEOUT_SECONDS);
                try (TupleQueryResult rs = q.evaluate()) {
                    while (rs.hasNext()) {
                        BindingSet row = rs.next();
                        for (String name : variableNames) {
                            Value v = row.getValue(name);
                            if (v instanceof IRI iri) {
                                if (resources.size() >= DESCRIBE_RESOURCE_CAP && !resources.contains(iri)) {
                                    truncated = true;
                                } else {
                                    resources.add(iri);
                                }
                            }
                        }
                        if (truncated) {
                            break;
                        }
                    }
                }
            }
        }
        List<IRI> iris = new ArrayList<>();
        for (Resource r : resources) {
            if (r instanceof IRI iri && safeForQuery(iri.stringValue())) {
                iris.add(iri);
            }
        }
        if (iris.size() > DESCRIBE_RESOURCE_CAP) {
            iris = new ArrayList<>(iris.subList(0, DESCRIBE_RESOURCE_CAP));
            truncated = true;
        }
        Set<Statement> out = new LinkedHashSet<>();
        Map<String, String> namespaces = new LinkedHashMap<>();
        for (int i = 0; i < iris.size() && !truncated; i += DESCRIBE_BATCH) {
            List<IRI> batch = iris.subList(i, Math.min(iris.size(), i + DESCRIBE_BATCH));
            SparqlUserQueryResult.Graph part = constructOf(conn, describeConstruct(batch), dataset, cap - out.size() + 1);
            for (Statement st : part.statements()) {
                if (out.size() >= cap && !out.contains(st)) {
                    truncated = true;
                    break;
                }
                out.add(st);
            }
            namespaces.putAll(part.namespaces());
            if (part.truncated()) {
                truncated = true;
            }
        }
        return new SparqlUserQueryResult.Graph(new ArrayList<>(out), namespaces, truncated);
    }

    private static boolean safeForQuery(String iri) {
        for (int i = 0; i < iri.length(); i++) {
            char c = iri.charAt(i);
            if (c <= 0x20 || c == '<' || c == '>' || c == '"' || c == '{' || c == '}' || c == '|'
                    || c == '^' || c == '`' || c == '\\') {
                return false;
            }
        }
        return true;
    }

    static String describeConstruct(List<IRI> batch) {
        StringBuilder sb = new StringBuilder("CONSTRUCT { ?s ?p ?o } WHERE { VALUES ?r {");
        for (IRI iri : batch) {
            sb.append(" <").append(iri.stringValue()).append('>');
        }
        sb.append(" } ");
        for (int level = 0; level <= DESCRIBE_BNODE_DEPTH; level++) {
            if (level > 0) {
                sb.append(" UNION ");
            }
            sb.append("{ ");
            String subject = "?r";
            for (int k = 1; k <= level; k++) {
                sb.append(subject).append(" ?q").append(k).append(" ?b").append(k).append(" . FILTER(isBlank(?b")
                        .append(k).append(")) ");
                subject = "?b" + k;
            }
            sb.append(subject).append(" ?p ?o . BIND(").append(subject).append(" AS ?s) } ");
        }
        sb.append('}');
        return sb.toString();
    }

    static String decodeUnicodeEscapes(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && (s.charAt(i + 1) == 'u' || s.charAt(i + 1) == 'U')) {
                int len = s.charAt(i + 1) == 'u' ? 4 : 8;
                if (i + 2 + len <= s.length()) {
                    try {
                        int cp = Integer.parseInt(s.substring(i + 2, i + 2 + len), 16);
                        out.appendCodePoint(cp);
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

    static String surrogateSelect(String text) {
        String t = decodeUnicodeEscapes(text);
        int d = SparqlText.indexOfKeyword(t, "DESCRIBE");
        if (d < 0) {
            return null;
        }
        int brace = SparqlText.indexOfOpenBrace(t, d);
        int where = firstWhere(t, d);
        int cut = brace < 0 ? where : (where < 0 ? brace : Math.min(brace, where));
        if (cut < 0) {
            return null;
        }
        return t.substring(0, d) + "SELECT * " + t.substring(cut);
    }

    private static int firstWhere(String t, int from) {
        int where = SparqlText.indexOfKeyword(t.substring(from), "WHERE");
        return where < 0 ? -1 : from + where;
    }

    private static final class DescribeShape extends AbstractQueryModelVisitor<RuntimeException> {
        final Set<String> projected = new LinkedHashSet<>();
        final Set<String> constantNames = new LinkedHashSet<>();
        final Set<Resource> constants = new LinkedHashSet<>();
        private boolean topSeen;
        private final Map<String, Value> extensionConstants = new LinkedHashMap<>();

        @Override
        public void meet(Projection node) {
            if (!topSeen) {
                topSeen = true;
                for (ProjectionElem e : node.getProjectionElemList().getElements()) {
                    projected.add(e.getName());
                }
            }
            super.meet(node);
        }

        @Override
        public void meet(Extension node) {
            for (ExtensionElem e : node.getElements()) {
                if (e.getExpr() instanceof ValueConstant vc) {
                    extensionConstants.put(e.getName(), vc.getValue());
                }
            }
            super.meet(node);
        }

        void resolve() {
            for (String name : projected) {
                Value v = extensionConstants.get(name);
                if (v instanceof IRI iri) {
                    constants.add(iri);
                    constantNames.add(name);
                }
            }
        }
    }
}
