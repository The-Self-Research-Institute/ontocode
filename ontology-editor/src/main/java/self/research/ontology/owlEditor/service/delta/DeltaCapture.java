package self.research.ontology.owlEditor.service.delta;

import self.research.ontology.owlEditor.service.Limit;
import self.research.ontology.owlEditor.service.TolerantRdfParsing;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.QueryResults;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class DeltaCapture {


    private final Repository repository;
    private final String graphUri;
    private final List<String> seeds;
    private final List<String> incoming;
    private final boolean whole;
    private final Model before;

    private DeltaCapture(Repository repository, String graphUri, List<String> seeds, List<String> incoming,
                         boolean whole, Model before) {
        this.repository = repository;
        this.graphUri = graphUri;
        this.seeds = seeds;
        this.incoming = incoming;
        this.whole = whole;
        this.before = before;
    }

    public static Optional<DeltaCapture> begin(Repository repository, String graphUri, Collection<String> candidateSeeds) {
        return begin(repository, graphUri, candidateSeeds, List.of());
    }

    public static Optional<DeltaCapture> begin(Repository repository, String graphUri, Collection<String> candidateSeeds,
                                               Collection<String> candidateIncoming) {
        List<String> seeds = usable(candidateSeeds);
        if (seeds.isEmpty() || !isUsableIri(graphUri)) {
            return Optional.empty();
        }
        return start(repository, graphUri, seeds, usable(candidateIncoming), false);
    }

    public static Optional<DeltaCapture> beginWholeGraph(Repository repository, String graphUri) {
        if (!isUsableIri(graphUri)) {
            return Optional.empty();
        }
        return start(repository, graphUri, List.of(), List.of(), true);
    }

    private static Optional<DeltaCapture> start(Repository repository, String graphUri, List<String> seeds,
                                                List<String> incoming, boolean whole) {
        Model before = read(repository, graphUri, seeds, incoming, whole);
        if (!acceptable(before, whole)) {
            return Optional.empty();
        }
        return Optional.of(new DeltaCapture(repository, graphUri, seeds, incoming, whole,
                CanonicalGraph.canonicalise(before)));
    }

    public record Finish(Optional<ExactDelta> delta, boolean unchanged) {}

    public Optional<ExactDelta> finish() {
        return finishDetailed().delta();
    }

    public Finish finishDetailed() {
        Model afterRaw = read(repository, graphUri, seeds, incoming, whole);
        if (!acceptable(afterRaw, whole)) {
            return new Finish(Optional.empty(), false);
        }
        Model after = CanonicalGraph.canonicalise(afterRaw);
        ExactDelta delta = ExactDelta.between(before, after);
        if (delta.isEmpty()) {
            return new Finish(Optional.empty(), true);
        }
        return new Finish(delta.isTooLarge() ? Optional.empty() : Optional.of(delta), false);
    }

    private static boolean acceptable(Model model, boolean whole) {
        if (whole && model.size() > Limit.WHOLE_GRAPH_MAX_TRIPLES.value()) {
            return false;
        }
        return (whole || CanonicalGraph.isComplete(model)) && DeltaSparql.isRenderable(model);
    }

    private static List<String> usable(Collection<String> candidates) {
        Set<String> clean = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate != null && isUsableIri(candidate)) {
                clean.add(candidate);
            }
        }
        return new ArrayList<>(clean);
    }

    static Model read(Repository repository, String graphUri, List<String> seeds, List<String> incoming, boolean whole) {
        String text = whole ? wholeGraphQuery(graphUri) : neighbourhoodQuery(graphUri, seeds, incoming);
        try (RepositoryConnection connection = TolerantRdfParsing.apply(repository.getConnection())) {
            GraphQuery query = connection.prepareGraphQuery(text);
            query.setIncludeInferred(false);
            return QueryResults.asModel(query.evaluate());
        }
    }

    static String wholeGraphQuery(String graphUri) {
        return "CONSTRUCT { ?s ?p ?o } FROM <" + graphUri + "> WHERE { ?s ?p ?o } LIMIT " + (Limit.WHOLE_GRAPH_MAX_TRIPLES.value() + 1);
    }

    static String neighbourhoodQuery(String graphUri, List<String> seeds, List<String> incoming) {
        StringBuilder values = new StringBuilder("VALUES ?seed {");
        for (String seed : seeds) {
            values.append(" <").append(seed).append(">");
        }
        values.append(" } ");
        StringBuilder query = new StringBuilder("CONSTRUCT { ?s ?p ?o } FROM <").append(graphUri).append("> WHERE { ");
        query.append("{ ").append(values).append("?seed ?p ?o . BIND(?seed AS ?s) }");
        for (int depth = 1; depth <= Limit.NEIGHBOURHOOD_DEPTH.value(); depth++) {
            query.append(" UNION { ").append(values);
            String previous = "?seed";
            for (int level = 1; level <= depth; level++) {
                String node = "?b" + level;
                query.append(previous).append(" ?e").append(level).append(' ').append(node)
                        .append(" . FILTER(").append(structural(node)).append(") ");
                previous = node;
            }
            query.append("BIND(").append(previous).append(" AS ?s) ").append(previous).append(" ?p ?o }");
        }
        if (!incoming.isEmpty()) {
            appendIncoming(query, incoming);
        }
        query.append(" }");
        return query.toString();
    }

    private static String structural(String node) {
        return "isBlank(" + node + ") || STRSTARTS(STR(" + node + "), \"" + CanonicalGraph.OWNED_NAMESPACE + "\")";
    }

    private static void appendIncoming(StringBuilder query, List<String> incoming) {
        StringBuilder values = new StringBuilder("VALUES ?target {");
        for (String iri : incoming) {
            values.append(" <").append(iri).append(">");
        }
        values.append(" } ");
        for (int depth = 0; depth <= Limit.NEIGHBOURHOOD_DEPTH.value(); depth++) {
            query.append(" UNION { ").append(values).append("?r0 ?pin ?target . FILTER(isBlank(?r0)) ");
            String previous = "?r0";
            for (int level = 1; level <= depth; level++) {
                String node = "?c" + level;
                query.append(previous).append(" ?f").append(level).append(' ').append(node)
                        .append(" . FILTER(").append(structural(node)).append(") ");
                previous = node;
            }
            query.append("BIND(").append(previous).append(" AS ?s) ").append(previous).append(" ?p ?o }");
        }
    }

    private static boolean isUsableIri(String iri) {
        if (iri.isEmpty()) {
            return false;
        }
        for (char c : iri.toCharArray()) {
            if (c <= ' ' || "<>\"{}|\\^`".indexOf(c) >= 0) {
                return false;
            }
        }
        return iri.contains(":");
    }
}
