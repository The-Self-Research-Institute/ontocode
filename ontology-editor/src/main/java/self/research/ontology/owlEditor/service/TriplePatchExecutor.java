package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.query.QueryResults;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
final class TriplePatchExecutor {

    enum Outcome { APPLIED, RESTORED_AFTER_MISMATCH }

    private final SparqlDatasetService datasetService;

    TriplePatchExecutor(SparqlDatasetService datasetService) {
        this.datasetService = datasetService;
    }

    Outcome apply(String projectId, TriplePatchPlanner.TriplePatch patch) {
        if (patch.subjects().isEmpty()) {
            return Outcome.APPLIED;
        }
        SparqlDatasetService.ProjectGraphTarget target = datasetService.graphTarget(projectId);
        String graph = target.graphUri();
        try (RepositoryConnection conn = target.repository().getConnection()) {
            execute(conn, forwardUpdate(graph, patch));
            Model actual = fetchClosure(conn, graph, patch.subjects(), patch.verifyDepth());
            if (Models.isomorphic(actual, patch.expected())) {
                return Outcome.APPLIED;
            }
            log.warn("[Assistant] Patched triples for project {} did not match the edited document ({} stored vs {} "
                    + "expected); undoing the patch and reimporting instead", projectId, actual.size(), patch.expected().size());
            execute(conn, inverseUpdate(graph, patch));
            return Outcome.RESTORED_AFTER_MISMATCH;
        }
    }

    static String forwardUpdate(String graph, TriplePatchPlanner.TriplePatch patch) {
        List<String> operations = new ArrayList<>();
        addData(operations, "DELETE DATA", graph, patch.removed());
        for (Map.Entry<IRI, Integer> entry : patch.treeDeleteDepth().entrySet()) {
            operations.addAll(treeDeletes(graph, entry.getKey(), entry.getValue()));
        }
        Model inserted = new LinkedHashModel(patch.added());
        inserted.addAll(patch.insertedTrees());
        addData(operations, "INSERT DATA", graph, inserted);
        return String.join(" ;\n", operations);
    }

    static String inverseUpdate(String graph, TriplePatchPlanner.TriplePatch patch) {
        List<String> operations = new ArrayList<>();
        Model undoAdded = new LinkedHashModel(patch.added());
        undoAdded.removeAll(patch.kept());
        addData(operations, "DELETE DATA", graph, undoAdded);
        for (IRI subject : patch.subjects()) {
            Model tree = BlankNodeTrees.treeOf(patch.insertedTrees(), subject);
            if (!tree.isEmpty()) {
                operations.addAll(treeDeletes(graph, subject, BlankNodeTrees.depth(tree, subject)));
            }
        }
        Model restored = new LinkedHashModel(patch.removed());
        restored.addAll(patch.restoredTrees());
        addData(operations, "INSERT DATA", graph, restored);
        return String.join(" ;\n", operations);
    }

    private static void execute(RepositoryConnection conn, String update) {
        if (!update.isBlank()) {
            conn.prepareUpdate(update).execute();
        }
    }

    private static void addData(List<String> operations, String keyword, String graph, Model model) {
        if (!model.isEmpty()) {
            operations.add(keyword + " { GRAPH <" + graph + "> {\n" + ntriples(model) + "} }");
        }
    }

    private static String ntriples(Model model) {
        StringWriter out = new StringWriter();
        Rio.write(model, out, RDFFormat.NTRIPLES);
        return out.toString();
    }

    static List<String> treeDeletes(String graph, IRI subject, int depth) {
        List<String> operations = new ArrayList<>();
        for (int level = depth; level >= 1; level--) {
            StringBuilder where = new StringBuilder();
            String node = chain(where, "<" + subject.stringValue() + ">", "d" + level, level);
            operations.add("DELETE { GRAPH <" + graph + "> { " + node + " ?p ?o } } WHERE { GRAPH <" + graph + "> { "
                    + where + node + " ?p ?o } }");
        }
        operations.add("DELETE { GRAPH <" + graph + "> { <" + subject.stringValue() + "> ?p ?o } } WHERE { GRAPH <"
                + graph + "> { <" + subject.stringValue() + "> ?p ?o FILTER(isBlank(?o)) } }");
        return operations;
    }

    private static String chain(StringBuilder where, String root, String prefix, int links) {
        String previous = root;
        for (int i = 1; i <= links; i++) {
            String next = "?" + prefix + "_n" + i;
            where.append(previous).append(" ?").append(prefix).append("_q").append(i).append(' ').append(next)
                    .append(" . FILTER(isBlank(").append(next).append(")) ");
            previous = next;
        }
        return previous;
    }

    static String closureQuery(String graph, Set<IRI> subjects, int depth) {
        StringBuilder template = new StringBuilder("?s ?p0 ?o0 . ");
        StringBuilder branches = new StringBuilder("{ ?s ?p0 ?o0 }");
        for (int level = 1; level <= depth; level++) {
            StringBuilder where = new StringBuilder();
            String node = chain(where, "?s", "v" + level, level);
            template.append(node).append(" ?p").append(level).append(" ?o").append(level).append(" . ");
            branches.append(" UNION { ").append(where).append(node).append(" ?p").append(level).append(" ?o")
                    .append(level).append(" }");
        }
        StringBuilder values = new StringBuilder();
        for (IRI subject : subjects) {
            values.append('<').append(subject.stringValue()).append("> ");
        }
        return "CONSTRUCT { " + template + "} WHERE { GRAPH <" + graph + "> { VALUES ?s { " + values + "} "
                + branches + " } }";
    }

    private static Model fetchClosure(RepositoryConnection conn, String graph, Set<IRI> subjects, int depth) {
        if (subjects.isEmpty()) {
            return new LinkedHashModel();
        }
        return QueryResults.asModel(conn.prepareGraphQuery(closureQuery(graph, subjects, depth)).evaluate());
    }
}
