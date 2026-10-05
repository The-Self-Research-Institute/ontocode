package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.Rio;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GraphStatementOps {

    private static final Map<String, String> EXPORT_PREFIXES = Map.of(
            "rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "rdfs", "http://www.w3.org/2000/01/rdf-schema#",
            "owl", "http://www.w3.org/2002/07/owl#",
            "xsd", "http://www.w3.org/2001/XMLSchema#");

    private GraphStatementOps() {
    }

    static List<Statement> read(RepositoryConnection conn, IRI graph) {
        List<Statement> statements = new ArrayList<>();
        try (RepositoryResult<Statement> result = conn.getStatements(null, null, null, false, graph)) {
            while (result.hasNext()) {
                statements.add(result.next());
            }
        }
        return statements;
    }

    static int copy(RepositoryConnection conn, String fromGraph, String toGraph) {
        IRI from = conn.getValueFactory().createIRI(fromGraph);
        IRI to = conn.getValueFactory().createIRI(toGraph);
        boolean autoCommit = conn.isAutoCommit();
        if (autoCommit) {
            conn.begin();
        }
        try {
            List<Statement> statements = read(conn, from);
            conn.add(statements, to);
            if (autoCommit) {
                conn.commit();
            }
            return statements.size();
        } catch (RuntimeException e) {
            if (autoCommit) {
                conn.rollback();
            }
            throw e;
        }
    }

    static String export(RepositoryConnection conn, String graphUri, RDFFormat format) {
        return export(conn, graphUri, format, Map.of());
    }

    static String export(RepositoryConnection conn, String graphUri, RDFFormat format, Map<String, String> extraPrefixes) {
        List<Statement> statements = read(conn, conn.getValueFactory().createIRI(graphUri));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RDFWriter writer = Rio.createWriter(format, out);
        writer.startRDF();
        EXPORT_PREFIXES.forEach(writer::handleNamespace);
        extraPrefixes.forEach((prefix, namespace) -> {
            if (!EXPORT_PREFIXES.containsKey(prefix)) {
                writer.handleNamespace(prefix, namespace);
            }
        });
        statements.forEach(writer::handleStatement);
        writer.endRDF();
        return out.toString(StandardCharsets.UTF_8);
    }

    static SparqlDatasetService.CappedSparqlResult selectCapped(RepositoryConnection conn, String queryText,
                                                                List<String> graphUris, int timeoutSeconds, int maxRows,
                                                                long maxBytesApprox) {
        TupleQuery query = conn.prepareTupleQuery(queryText);
        SimpleDataset dataset = new SimpleDataset();
        graphUris.forEach(g -> dataset.addDefaultGraph(SimpleValueFactory.getInstance().createIRI(g)));
        query.setDataset(dataset);
        query.setIncludeInferred(false);
        query.setMaxExecutionTime(timeoutSeconds);
        List<Map<String, String>> rows = new ArrayList<>();
        String capExceeded = null;
        long approxBytes = 0;
        try (TupleQueryResult result = query.evaluate()) {
            List<String> vars = new ArrayList<>(result.getBindingNames());
            while (result.hasNext() && capExceeded == null) {
                if (rows.size() >= maxRows) {
                    capExceeded = "ROW_CAP_EXCEEDED";
                    continue;
                }
                BindingSet binding = result.next();
                Map<String, String> row = new LinkedHashMap<>();
                for (String var : vars) {
                    String value = stringOf(binding.getValue(var));
                    approxBytes += var.length() + (value != null ? value.length() : 0);
                    row.put(var, value);
                }
                if (approxBytes > maxBytesApprox) {
                    capExceeded = "BYTE_CAP_EXCEEDED";
                } else {
                    rows.add(row);
                }
            }
            return new SparqlDatasetService.CappedSparqlResult(vars, rows, capExceeded != null, capExceeded);
        }
    }

    private static String stringOf(Value value) {
        return value == null ? null : value.stringValue();
    }
}
