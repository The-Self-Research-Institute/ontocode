package self.research.ontology.owlEditor.service;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Syntax;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.modify.request.QuadAcc;
import org.apache.jena.sparql.modify.request.QuadDataAcc;
import org.apache.jena.sparql.modify.request.Target;
import org.apache.jena.sparql.modify.request.UpdateAdd;
import org.apache.jena.sparql.modify.request.UpdateBinaryOp;
import org.apache.jena.sparql.modify.request.UpdateClear;
import org.apache.jena.sparql.modify.request.UpdateCopy;
import org.apache.jena.sparql.modify.request.UpdateCreate;
import org.apache.jena.sparql.modify.request.UpdateData;
import org.apache.jena.sparql.modify.request.UpdateDataDelete;
import org.apache.jena.sparql.modify.request.UpdateDataInsert;
import org.apache.jena.sparql.modify.request.UpdateDeleteWhere;
import org.apache.jena.sparql.modify.request.UpdateDrop;
import org.apache.jena.sparql.modify.request.UpdateDropClear;
import org.apache.jena.sparql.modify.request.UpdateLoad;
import org.apache.jena.sparql.modify.request.UpdateModify;
import org.apache.jena.sparql.modify.request.UpdateMove;
import org.apache.jena.sparql.core.TriplePath;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.apache.jena.sparql.syntax.ElementPathBlock;
import org.apache.jena.sparql.syntax.ElementTriplesBlock;
import org.apache.jena.sparql.syntax.syntaxtransform.ElementTransform;
import org.apache.jena.sparql.syntax.syntaxtransform.ElementTransformCopyBase;
import org.apache.jena.sparql.syntax.syntaxtransform.ElementTransformer;
import org.apache.jena.sparql.syntax.syntaxtransform.ExprTransformApplyElementTransform;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.update.Update;
import org.apache.jena.update.UpdateFactory;
import org.apache.jena.update.UpdateRequest;
import self.research.ontology.common.admission.WorkKind;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class SparqlUpdateScoper {

    static final String MSG_LOAD = "LOAD is not supported; updates cannot fetch data from other locations.";
    static final String MSG_DATASET =
            "WITH, USING and USING NAMED are not supported; updates run against the project you selected.";
    static final String MSG_VARIABLE_GRAPH =
            "GRAPH with a variable name is not supported in the changes an update makes.";
    static final String MSG_WHOLE_STORE =
            "CLEAR, DROP, ADD, MOVE and COPY only work on a named graph of the project you selected, not on DEFAULT, NAMED or ALL.";
    static final String MSG_UNSUPPORTED = "This kind of update operation is not supported.";

    private SparqlUpdateScoper() {
    }

    static String graphMessage(String iri) {
        return "GRAPH <" + iri
                + "> is not available; updates only change the project you selected and your own draft when it is open.";
    }

    public static void checkSize(String text, SparqlUpdateLimits limits) {
        if (text == null) {
            return;
        }
        if (text.length() > limits.maxBytes()
                || (text.length() * 3L > limits.maxBytes()
                && text.getBytes(StandardCharsets.UTF_8).length > limits.maxBytes())) {
            throw new SparqlUpdateTooLargeException("The update is larger than the allowed " + limits.maxBytes()
                    + " bytes; send it in smaller updates.");
        }
    }

    public static String scope(String text, String targetGraph, Collection<String> extraGraphs,
                               Supplier<Collection<String>> partitionGraphs, SparqlUpdateLimits limits,
                               HeavyWorkAdmission admission, String userId) {
        checkSize(text, limits);
        long estimate = text.getBytes(StandardCharsets.UTF_8).length * SparqlUpdateLimits.PARSE_HEAP_FACTOR;
        if (admission == null || estimate < HeavyWorkAdmission.MIN_ESTIMATE_BYTES) {
            return scope(text, targetGraph, extraGraphs, partitionGraphs, limits);
        }
        try (HeavyWorkAdmission.Hold ignored = admission.admit(userId, WorkKind.SPARQL_QUERY, estimate)) {
            return scope(text, targetGraph, extraGraphs, partitionGraphs, limits);
        }
    }

    public static String scope(String text, String targetGraph, Collection<String> extraGraphs,
                               Supplier<Collection<String>> partitionGraphs, SparqlUpdateLimits limits) {
        checkSize(text, limits);
        UpdateRequest request = parse(text);
        if (request.getOperations().size() > limits.maxOperations()) {
            throw new SparqlQueryRejectedException("The update has more than " + limits.maxOperations()
                    + " operations; send it in smaller updates.");
        }
        Graphs graphs = new Graphs(targetGraph, extraGraphs, partitionGraphs);
        UpdateRequest scoped = new UpdateRequest();
        scoped.setPrefixMapping(request.getPrefixMapping());
        for (Update op : request.getOperations()) {
            scoped.add(scopeOperation(op, graphs, limits));
        }
        return scoped.toString();
    }

    static final String NO_BASE = "urn:x-ontocode:no-base:";
    static final int INLINE_PARSE_CHARS = 32 * 1024;
    static final long STACK_BYTES_PER_CHAR = 2048L;
    static final long MIN_PARSE_STACK = 16L * 1024 * 1024;
    static final long MAX_PARSE_STACK = 1024L * 1024 * 1024;

    private static UpdateRequest parse(String text) {
        if (text.length() <= INLINE_PARSE_CHARS) {
            return parseInline(text);
        }
        long stack = Math.max(MIN_PARSE_STACK, Math.min(MAX_PARSE_STACK, text.length() * STACK_BYTES_PER_CHAR));
        Object[] outcome = new Object[1];
        Thread worker = new Thread(null, () -> {
            try {
                outcome[0] = parseInline(text);
            } catch (Throwable t) {
                outcome[0] = t;
            }
        }, "sparql-update-parse", stack);
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SparqlQueryRejectedException("The update was interrupted before it could be read.");
        }
        if (outcome[0] instanceof RuntimeException e) {
            throw e;
        }
        if (outcome[0] instanceof Throwable t) {
            throw new SparqlQueryRejectedException("The update could not be parsed: " + SparqlQueryGuard.firstLine(t.getMessage()));
        }
        return (UpdateRequest) outcome[0];
    }

    private static UpdateRequest parseInline(String text) {
        try {
            return UpdateFactory.create(text, NO_BASE, Syntax.syntaxSPARQL_11);
        } catch (StackOverflowError e) {
            throw new SparqlQueryRejectedException("The update is nested or long beyond what can be read safely; send it in smaller updates.");
        } catch (RuntimeException e) {
            throw new SparqlQueryRejectedException(
                    "The update could not be parsed: " + SparqlQueryGuard.firstLine(e.getMessage()));
        }
    }

    private static final class Graphs {
        private final Node target;
        private final Map<String, String> allowed = new LinkedHashMap<>();
        private final Supplier<Collection<String>> partitions;
        private boolean partitionsLoaded;

        Graphs(String targetGraph, Collection<String> extra, Supplier<Collection<String>> partitions) {
            this.target = NodeFactory.createURI(targetGraph);
            add(targetGraph);
            if (extra != null) {
                extra.forEach(this::add);
            }
            this.partitions = partitions;
        }

        private void add(String graph) {
            allowed.putIfAbsent(SparqlQueryGuard.normalizeIri(graph), graph);
        }

        boolean contains(Node node) {
            if (!node.isURI()) {
                return false;
            }
            String key = SparqlQueryGuard.normalizeIri(node.getURI());
            if (allowed.containsKey(key)) {
                return true;
            }
            loadPartitions();
            return allowed.containsKey(key);
        }

        Collection<String> all() {
            return allowed.values();
        }

        boolean loadMore() {
            if (partitionsLoaded || partitions == null) {
                return false;
            }
            loadPartitions();
            return true;
        }

        private void loadPartitions() {
            if (!partitionsLoaded && partitions != null) {
                partitionsLoaded = true;
                Collection<String> found = partitions.get();
                if (found != null) {
                    found.forEach(this::add);
                }
            }
        }

        Node requireNamed(Node node) {
            if (node.isVariable()) {
                throw new SparqlQueryRejectedException(MSG_VARIABLE_GRAPH);
            }
            if (!contains(node)) {
                throw new SparqlQueryRejectedException(graphMessage(node.isURI() ? node.getURI() : node.toString()));
            }
            return node;
        }
    }

    private static Update scopeOperation(Update op, Graphs graphs, SparqlUpdateLimits limits) {
        if (op instanceof UpdateLoad) {
            throw new SparqlQueryRejectedException(MSG_LOAD);
        }
        if (op instanceof UpdateData data) {
            QuadDataAcc acc = new QuadDataAcc(scopeQuads(data.getQuads(), graphs, limits));
            return data instanceof UpdateDataInsert ? new UpdateDataInsert(acc) : new UpdateDataDelete(acc);
        }
        if (op instanceof UpdateDeleteWhere deleteWhere) {
            return new UpdateDeleteWhere(new QuadAcc(scopeQuads(deleteWhere.getQuads(), graphs, limits)));
        }
        if (op instanceof UpdateModify modify) {
            return scopeModify(modify, graphs, limits);
        }
        if (op instanceof UpdateDropClear dropClear) {
            requireOneNamed(dropClear.getTarget(), graphs);
            return dropClear instanceof UpdateClear
                    ? new UpdateClear(dropClear.getTarget(), dropClear.isSilent())
                    : new UpdateDrop(dropClear.getTarget(), dropClear.isSilent());
        }
        if (op instanceof UpdateCreate create) {
            graphs.requireNamed(create.getGraph());
            return create;
        }
        if (op instanceof UpdateBinaryOp binary) {
            requireOneNamed(binary.getSrc(), graphs);
            requireOneNamed(binary.getDest(), graphs);
            if (binary instanceof UpdateAdd) {
                return new UpdateAdd(binary.getSrc(), binary.getDest(), binary.isSilent());
            }
            if (binary instanceof UpdateMove) {
                return new UpdateMove(binary.getSrc(), binary.getDest(), binary.isSilent());
            }
            if (binary instanceof UpdateCopy) {
                return new UpdateCopy(binary.getSrc(), binary.getDest(), binary.isSilent());
            }
        }
        throw new SparqlQueryRejectedException(MSG_UNSUPPORTED);
    }

    private static void requireOneNamed(Target target, Graphs graphs) {
        if (!target.isOneNamedGraph()) {
            throw new SparqlQueryRejectedException(MSG_WHOLE_STORE);
        }
        graphs.requireNamed(target.getGraph());
    }

    private static List<Quad> scopeQuads(List<Quad> quads, Graphs graphs, SparqlUpdateLimits limits) {
        if (quads.size() > limits.maxQuads()) {
            throw new SparqlQueryRejectedException("The update changes more than " + limits.maxQuads()
                    + " statements in one operation; send it in smaller updates.");
        }
        List<Quad> out = new ArrayList<>(quads.size());
        for (Quad quad : quads) {
            if (quad.isDefaultGraph()) {
                out.add(Quad.create(graphs.target, quad.getSubject(), quad.getPredicate(), quad.getObject()));
            } else {
                graphs.requireNamed(quad.getGraph());
                out.add(quad);
            }
        }
        return out;
    }

    private static Element keepListTriplesExplicit(Element where) {
        if (where == null) {
            return null;
        }
        ElementTransform transform = new ElementTransformCopyBase() {
            @Override
            public Element transform(ElementPathBlock block) {
                return explicit(block.getPattern().iterator());
            }

            @Override
            public Element transform(ElementTriplesBlock block) {
                List<TriplePath> paths = new ArrayList<>();
                block.getPattern().getList().forEach(t -> paths.add(new TriplePath(t)));
                return explicit(paths.iterator());
            }
        };
        return ElementTransformer.transform(where, transform, new ExprTransformApplyElementTransform(transform));
    }

    private static Element explicit(java.util.Iterator<TriplePath> paths) {
        ElementGroup out = new ElementGroup();
        ElementPathBlock block = new ElementPathBlock();
        while (paths.hasNext()) {
            TriplePath tp = paths.next();
            if (tp.isTriple() && (RDF.Nodes.first.equals(tp.getPredicate()) || RDF.Nodes.rest.equals(tp.getPredicate()))) {
                ElementPathBlock single = new ElementPathBlock();
                single.addTriplePath(tp);
                ElementGroup wrapped = new ElementGroup();
                wrapped.addElement(single);
                out.addElement(wrapped);
            } else {
                block.addTriplePath(tp);
            }
        }
        if (!block.isEmpty()) {
            out.getElements().add(0, block);
        }
        return out;
    }

    private static Update scopeModify(UpdateModify modify, Graphs graphs, SparqlUpdateLimits limits) {
        if (modify.getWithIRI() != null || !modify.getUsing().isEmpty() || !modify.getUsingNamed().isEmpty()) {
            throw new SparqlQueryRejectedException(MSG_DATASET);
        }
        UpdateModify out = new UpdateModify();
        out.setHasDeleteClause(modify.hasDeleteClause());
        out.setHasInsertClause(modify.hasInsertClause());
        for (Quad q : scopeQuads(modify.getDeleteQuads(), graphs, limits)) {
            out.getDeleteAcc().addQuad(q);
        }
        for (Quad q : scopeQuads(modify.getInsertQuads(), graphs, limits)) {
            out.getInsertAcc().addQuad(q);
        }
        try {
            JenaQueryGuard.checkUpdatePattern(modify.getWherePattern(), new java.util.HashSet<>(graphs.all()));
        } catch (SparqlQueryRejectedException first) {
            if (!graphs.loadMore()) {
                throw first;
            }
            JenaQueryGuard.checkUpdatePattern(modify.getWherePattern(), new java.util.HashSet<>(graphs.all()));
        }
        out.setElement(keepListTriplesExplicit(modify.getWherePattern()));
        out.addUsing(graphs.target);
        for (String graph : graphs.all()) {
            out.addUsingNamed(NodeFactory.createURI(graph));
        }
        return out;
    }
}
