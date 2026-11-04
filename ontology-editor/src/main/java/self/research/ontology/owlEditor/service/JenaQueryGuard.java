package self.research.ontology.owlEditor.service;

import org.apache.jena.graph.Node;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.SortCondition;
import org.apache.jena.query.Syntax;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpGraph;
import org.apache.jena.sparql.algebra.op.OpPath;
import org.apache.jena.sparql.algebra.op.OpPropFunc;
import org.apache.jena.sparql.algebra.op.OpQuad;
import org.apache.jena.sparql.algebra.op.OpQuadBlock;
import org.apache.jena.sparql.algebra.op.OpQuadPattern;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.algebra.op.OpTriple;
import org.apache.jena.sparql.algebra.walker.Walker;
import org.apache.jena.sparql.core.TriplePath;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.expr.E_Function;
import org.apache.jena.sparql.expr.E_Call;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprAggregator;
import org.apache.jena.sparql.expr.ExprFunction;
import org.apache.jena.sparql.expr.ExprFunctionN;
import org.apache.jena.sparql.expr.ExprFunctionOp;
import org.apache.jena.sparql.expr.ExprList;
import org.apache.jena.sparql.expr.ExprVar;
import org.apache.jena.sparql.expr.ExprVisitorBase;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.expr.aggregate.AggCustom;
import org.apache.jena.sparql.expr.aggregate.Aggregator;
import org.apache.jena.sparql.path.P_NegPropSet;
import org.apache.jena.sparql.path.P_Path0;
import org.apache.jena.sparql.path.P_Path1;
import org.apache.jena.sparql.path.P_Path2;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.pfunction.PropertyFunctionRegistry;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementAssign;
import org.apache.jena.sparql.syntax.ElementBind;
import org.apache.jena.sparql.syntax.ElementData;
import org.apache.jena.sparql.syntax.ElementExists;
import org.apache.jena.sparql.syntax.ElementFilter;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.apache.jena.sparql.syntax.ElementMinus;
import org.apache.jena.sparql.syntax.ElementNamedGraph;
import org.apache.jena.sparql.syntax.ElementNotExists;
import org.apache.jena.sparql.syntax.ElementOptional;
import org.apache.jena.sparql.syntax.ElementPathBlock;
import org.apache.jena.sparql.syntax.ElementService;
import org.apache.jena.sparql.syntax.ElementSubQuery;
import org.apache.jena.sparql.syntax.ElementTriplesBlock;
import org.apache.jena.sparql.syntax.ElementUnion;
import org.apache.jena.update.UpdateFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class JenaQueryGuard {

    private JenaQueryGuard() {}

    public static SparqlQueryGuard.Kind check(String decodedText, Set<String> authorizedGraphs) {
        Set<String> allowed = authorizedGraphs.stream().map(SparqlQueryGuard::normalizeIri).collect(Collectors.toSet());
        Query query;
        try {
            query = QueryFactory.create(decodedText, Syntax.syntaxSPARQL_11);
        } catch (RuntimeException | Error e) {
            if (isUpdate(decodedText)) {
                throw new SparqlQueryRejectedException(SparqlQueryGuard.MSG_UPDATE);
            }
            throw new SparqlQueryRejectedException(
                    "The query could not be parsed: " + SparqlQueryGuard.firstLine(e.getMessage()));
        }
        SparqlQueryGuard.Kind kind;
        if (query.isSelectType()) {
            kind = SparqlQueryGuard.Kind.SELECT;
        } else if (query.isAskType()) {
            kind = SparqlQueryGuard.Kind.ASK;
        } else if (query.isConstructType()) {
            kind = SparqlQueryGuard.Kind.CONSTRUCT;
        } else if (query.isDescribeType()) {
            kind = SparqlQueryGuard.Kind.DESCRIBE;
        } else {
            throw new SparqlQueryRejectedException(SparqlQueryGuard.MSG_FORMS);
        }
        new Walk(allowed).query(query);
        Op algebra;
        try {
            algebra = Algebra.compile(query);
        } catch (RuntimeException | Error e) {
            throw new SparqlQueryRejectedException(
                    "The query could not be parsed: " + SparqlQueryGuard.firstLine(e.getMessage()));
        }
        AlgebraCheck check = new AlgebraCheck(allowed);
        Walker.walk(algebra, check, check.exprs);
        return kind;
    }

    public static void checkUpdatePattern(Element where, Set<String> authorizedGraphs) {
        if (where == null) {
            return;
        }
        Set<String> allowed = authorizedGraphs.stream().map(SparqlQueryGuard::normalizeIri).collect(Collectors.toSet());
        new Walk(allowed).element(where);
        Op algebra;
        try {
            algebra = Algebra.compile(where);
        } catch (RuntimeException | Error e) {
            throw new SparqlQueryRejectedException(
                    "The update could not be parsed: " + SparqlQueryGuard.firstLine(e.getMessage()));
        }
        AlgebraCheck check = new AlgebraCheck(allowed);
        Walker.walk(algebra, check, check.exprs);
    }

    private static boolean isUpdate(String text) {
        try {
            UpdateFactory.create(text, Syntax.syntaxSPARQL_11);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void graph(Node node, Set<String> allowed) {
        if (node != null && !node.isVariable()) {
            String iri = node.isURI() ? node.getURI() : node.toString();
            if (!node.isURI() || !allowed.contains(SparqlQueryGuard.normalizeIri(iri))) {
                throw new SparqlQueryRejectedException(SparqlQueryGuard.graphMessage(iri));
            }
        }
    }

    private static void predicate(Node node) {
        if (node != null && node.isURI()) {
            String iri = node.getURI();
            if (SparqlQueryGuard.isExtensionIri(iri) || PropertyFunctionRegistry.get().isRegistered(iri)) {
                throw new SparqlQueryRejectedException(SparqlQueryGuard.MSG_PROPERTY_FUNCTION);
            }
        }
    }

    private static void path(Path p) {
        if (p instanceof P_Path0 link) {
            predicate(link.getNode());
        } else if (p instanceof P_NegPropSet neg) {
            for (P_Path0 n : neg.getNodes()) {
                predicate(n.getNode());
            }
        } else if (p instanceof P_Path1 one) {
            path(one.getSubPath());
        } else if (p instanceof P_Path2 two) {
            path(two.getLeft());
            path(two.getRight());
        } else {
            throw new SparqlQueryRejectedException(SparqlQueryGuard.MSG_UNSUPPORTED_SYNTAX);
        }
    }

    private static void function(String iri) {
        if (!SparqlQueryGuard.functionAllowed(iri)) {
            throw new SparqlQueryRejectedException("The function <" + iri + "> is not supported.");
        }
    }

    private static void aggregator(Aggregator agg) {
        if (agg instanceof AggCustom custom && !SparqlQueryGuard.functionAllowed(custom.getIRI())) {
            throw new SparqlQueryRejectedException("The aggregate function <" + custom.getIRI() + "> is not supported.");
        }
    }

    private static final class AlgebraCheck extends OpVisitorBase {
        private final Set<String> allowed;
        final ExprVisitorBase exprs = new ExprVisitorBase() {
            @Override
            public void visit(ExprFunctionN f) {
                if (f instanceof E_Function fn) {
                    function(fn.getFunctionIRI());
                }
                if (f instanceof E_Call) {
                    throw new SparqlQueryRejectedException(SparqlQueryGuard.MSG_UNSUPPORTED_SYNTAX);
                }
            }

            @Override
            public void visit(ExprAggregator agg) {
                aggregator(agg.getAggregator());
            }
        };

        AlgebraCheck(Set<String> allowed) {
            this.allowed = allowed;
        }

        @Override
        public void visit(OpService op) {
            throw new SparqlQueryRejectedException(SparqlQueryGuard.MSG_SERVICE);
        }

        @Override
        public void visit(OpGraph op) {
            graph(op.getNode(), allowed);
        }

        @Override
        public void visit(OpQuad op) {
            graph(op.getQuad().getGraph(), allowed);
        }

        @Override
        public void visit(OpQuadPattern op) {
            graph(op.getGraphNode(), allowed);
        }

        @Override
        public void visit(OpQuadBlock op) {
            throw new SparqlQueryRejectedException(SparqlQueryGuard.MSG_UNSUPPORTED_SYNTAX);
        }

        @Override
        public void visit(OpPropFunc op) {
            throw new SparqlQueryRejectedException(SparqlQueryGuard.MSG_PROPERTY_FUNCTION);
        }

        @Override
        public void visit(OpPath op) {
            TriplePath tp = op.getTriplePath();
            if (tp.isTriple()) {
                predicate(tp.getPredicate());
            } else {
                path(tp.getPath());
            }
        }

        @Override
        public void visit(OpBGP op) {
            op.getPattern().getList().forEach(t -> predicate(t.getPredicate()));
        }

        @Override
        public void visit(OpTriple op) {
            predicate(op.getTriple().getPredicate());
        }
    }

    private static final class Walk {
        private final Set<String> allowed;

        Walk(Set<String> allowed) {
            this.allowed = allowed;
        }

        void query(Query q) {
            if (q.hasDatasetDescription()) {
                throw new SparqlQueryRejectedException(SparqlQueryGuard.MSG_DATASET);
            }
            if (q.getQueryPattern() != null) {
                element(q.getQueryPattern());
            }
            for (Map.Entry<Var, Expr> e : q.getProject().getExprs().entrySet()) {
                expr(e.getValue());
            }
            for (Map.Entry<Var, Expr> e : q.getGroupBy().getExprs().entrySet()) {
                expr(e.getValue());
            }
            if (q.hasHaving()) {
                exprs(q.getHavingExprs());
            }
            if (q.getOrderBy() != null) {
                for (SortCondition sc : q.getOrderBy()) {
                    expr(sc.getExpression());
                }
            }
            for (ExprAggregator agg : q.getAggregators()) {
                expr(agg);
            }
            if (q.isConstructType() && q.getConstructTemplate() != null) {
                q.getConstructTemplate().getTriples().forEach(t -> predicate(t.getPredicate()));
            }
        }

        void exprs(Iterable<Expr> list) {
            for (Expr e : list) {
                expr(e);
            }
        }

        void expr(Expr e) {
            if (e == null) {
                return;
            }
            if (e instanceof ExprAggregator agg) {
                aggregator(agg.getAggregator());
                ExprList args = agg.getAggregator().getExprList();
                if (args != null) {
                    exprs(args);
                }
                return;
            }
            if (e instanceof ExprFunctionOp op) {
                element(op.getElement());
                return;
            }
            if (e instanceof E_Function fn) {
                function(fn.getFunctionIRI());
            }
            if (e instanceof E_Call) {
                throw new SparqlQueryRejectedException(SparqlQueryGuard.MSG_UNSUPPORTED_SYNTAX);
            }
            if (e instanceof ExprFunction f) {
                for (int i = 1; i <= f.numArgs(); i++) {
                    expr(f.getArg(i));
                }
                return;
            }
            if (!(e instanceof ExprVar) && !(e instanceof NodeValue)) {
                throw new SparqlQueryRejectedException(SparqlQueryGuard.MSG_UNSUPPORTED_SYNTAX);
            }
        }

        void element(Element el) {
            if (el == null) {
                return;
            }
            if (el instanceof ElementGroup g) {
                g.getElements().forEach(this::element);
            } else if (el instanceof ElementUnion u) {
                u.getElements().forEach(this::element);
            } else if (el instanceof ElementOptional o) {
                element(o.getOptionalElement());
            } else if (el instanceof ElementMinus m) {
                element(m.getMinusElement());
            } else if (el instanceof ElementExists x) {
                element(x.getElement());
            } else if (el instanceof ElementNotExists x) {
                element(x.getElement());
            } else if (el instanceof ElementNamedGraph ng) {
                graph(ng.getGraphNameNode(), allowed);
                element(ng.getElement());
            } else if (el instanceof ElementService) {
                throw new SparqlQueryRejectedException(SparqlQueryGuard.MSG_SERVICE);
            } else if (el instanceof ElementSubQuery sq) {
                query(sq.getQuery());
            } else if (el instanceof ElementFilter f) {
                expr(f.getExpr());
            } else if (el instanceof ElementBind b) {
                expr(b.getExpr());
            } else if (el instanceof ElementAssign a) {
                expr(a.getExpr());
            } else if (el instanceof ElementData) {
                return;
            } else if (el instanceof ElementTriplesBlock tb) {
                tb.getPattern().getList().forEach(t -> predicate(t.getPredicate()));
            } else if (el instanceof ElementPathBlock pb) {
                for (Iterator<TriplePath> it = pb.patternElts(); it.hasNext(); ) {
                    TriplePath tp = it.next();
                    if (tp.isTriple()) {
                        predicate(tp.getPredicate());
                    } else {
                        path(tp.getPath());
                    }
                }
            } else {
                throw new SparqlQueryRejectedException(SparqlQueryGuard.MSG_UNSUPPORTED_SYNTAX);
            }
        }
    }
}
