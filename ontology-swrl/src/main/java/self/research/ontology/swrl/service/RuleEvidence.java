package self.research.ontology.swrl.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLBuiltInAtom;
import org.semanticweb.owlapi.model.SWRLClassAtom;
import org.semanticweb.owlapi.model.SWRLDArgument;
import org.semanticweb.owlapi.model.SWRLDataPropertyAtom;
import org.semanticweb.owlapi.model.SWRLIArgument;
import org.semanticweb.owlapi.model.SWRLIndividualArgument;
import org.semanticweb.owlapi.model.SWRLLiteralArgument;
import org.semanticweb.owlapi.model.SWRLObjectPropertyAtom;
import org.semanticweb.owlapi.model.SWRLVariable;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import org.swrlapi.builtins.arguments.SWRLBuiltInArgument;
import org.swrlapi.builtins.arguments.SWRLLiteralBuiltInArgument;
import org.swrlapi.builtins.arguments.SWRLNamedIndividualBuiltInArgument;
import org.swrlapi.builtins.arguments.SWRLVariableBuiltInArgument;
import org.swrlapi.core.SWRLAPIBuiltInAtom;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RuleEvidence {

    static final int CANDIDATE_LIMIT = 1000;

    private static final OWLDataFactory FACTORY = OWLManager.getOWLDataFactory();
    private static final Set<String> COMPARATORS = Set.of("greaterThan", "lessThan", "greaterThanOrEqual",
            "lessThanOrEqual", "equal", "notEqual");
    private static final Set<String> ARITHMETIC = Set.of("add", "subtract", "multiply", "divide", "mod");
    private static final Set<String> STRING_TESTS = Set.of("contains");
    private static final Set<String> CONCAT = Set.of("stringConcat");
    private static final Set<String> NUMERIC_TYPES = Set.of("integer", "int", "long", "short", "byte", "decimal",
            "double", "float", "nonNegativeInteger", "positiveInteger", "negativeInteger", "nonPositiveInteger",
            "unsignedInt", "unsignedLong", "unsignedShort", "unsignedByte");
    private static final Set<String> INTEGER_TYPES = Set.of("integer", "int", "long", "short", "byte",
            "nonNegativeInteger", "positiveInteger", "negativeInteger", "nonPositiveInteger", "unsignedInt",
            "unsignedLong", "unsignedShort", "unsignedByte");

    private RuleEvidence() {
    }

    static final class Unsupported extends Exception {
        Unsupported(String message) {
            super(message);
        }
    }

    record Term(String variable, Object constant) {
        boolean isVariable() {
            return variable != null;
        }
    }

    record Step(String kind, IRI predicate, String builtin, List<Term> args, int index) {
        boolean isBuiltin() {
            return "builtin".equals(kind);
        }

        Step withIndex(int newIndex) {
            return new Step(kind, predicate, builtin, args, newIndex);
        }

        String text() {
            StringBuilder out = new StringBuilder();
            out.append("builtin".equals(kind) ? "swrlb:" + builtin : predicate.getShortForm()).append('(');
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                Term t = args.get(i);
                out.append(t.isVariable() ? "?" + t.variable() : display(t.constant()));
            }
            return out.append(')').toString();
        }
    }

    static final class Facts {
        final Map<IRI, Map<IRI, Boolean>> classes = new HashMap<>();
        final Map<IRI, List<Object[]>> objects = new HashMap<>();
        final Map<IRI, List<Object[]>> data = new HashMap<>();

        static Facts of(Set<OWLAxiom> asserted, Set<OWLAxiom> inferred) {
            Facts facts = new Facts();
            for (OWLAxiom axiom : inferred) {
                facts.add(axiom, false);
            }
            for (OWLAxiom axiom : asserted) {
                facts.add(axiom, true);
            }
            return facts;
        }

        private void add(OWLAxiom axiom, boolean isAsserted) {
            if (axiom instanceof OWLClassAssertionAxiom a) {
                if (!a.getClassExpression().isAnonymous() && a.getIndividual().isNamed()) {
                    IRI cls = a.getClassExpression().asOWLClass().getIRI();
                    IRI ind = a.getIndividual().asOWLNamedIndividual().getIRI();
                    classes.computeIfAbsent(cls, k -> new LinkedHashMap<>()).merge(ind, isAsserted, Boolean::logicalOr);
                }
            } else if (axiom instanceof OWLObjectPropertyAssertionAxiom a) {
                if (!a.getProperty().isAnonymous() && a.getSubject().isNamed() && a.getObject().isNamed()) {
                    addEdge(objects, a.getProperty().asOWLObjectProperty().getIRI(),
                            a.getSubject().asOWLNamedIndividual().getIRI(),
                            a.getObject().asOWLNamedIndividual().getIRI(), isAsserted);
                }
            } else if (axiom instanceof OWLDataPropertyAssertionAxiom a) {
                if (!a.getProperty().isAnonymous() && a.getSubject().isNamed()) {
                    addEdge(data, a.getProperty().asOWLDataProperty().getIRI(),
                            a.getSubject().asOWLNamedIndividual().getIRI(), a.getObject(), isAsserted);
                }
            }
        }

        private static void addEdge(Map<IRI, List<Object[]>> target, IRI property, Object subject, Object object,
                                    boolean isAsserted) {
            List<Object[]> edges = target.computeIfAbsent(property, k -> new ArrayList<>());
            for (Object[] edge : edges) {
                if (edge[0].equals(subject) && valuesEqual(edge[1], object)) {
                    edge[2] = (Boolean) edge[2] || isAsserted;
                    return;
                }
            }
            edges.add(new Object[]{subject, object, isAsserted});
        }

        boolean isClassFactAsserted(IRI cls, IRI ind) {
            Map<IRI, Boolean> members = classes.get(cls);
            return members != null && Boolean.TRUE.equals(members.get(ind));
        }

        boolean hasClass(IRI cls, IRI ind) {
            Map<IRI, Boolean> members = classes.get(cls);
            return members != null && members.containsKey(ind);
        }

        boolean edgeAsserted(Map<IRI, List<Object[]>> source, IRI property, Object subject, Object object) {
            for (Object[] edge : source.getOrDefault(property, List.of())) {
                if (edge[0].equals(subject) && valuesEqual(edge[1], object)) {
                    return (Boolean) edge[2];
                }
            }
            return false;
        }
    }

    record Failure(int satisfied, int stepIndex, String result, String detail, Map<String, Object> binding,
                   boolean[] done) {
    }

    static List<Step> parse(List<SWRLAtom> atoms) throws Unsupported {
        List<Step> steps = new ArrayList<>();
        for (SWRLAtom atom : atoms) {
            steps.add(parseAtom(atom, steps.size()));
        }
        return steps;
    }

    private static Step parseAtom(SWRLAtom atom, int index) throws Unsupported {
        if (atom instanceof SWRLClassAtom c) {
            if (c.getPredicate().isAnonymous()) {
                throw new Unsupported("class expression " + c.getPredicate());
            }
            return new Step("class", c.getPredicate().asOWLClass().getIRI(), null, List.of(iTerm(c.getArgument())), index);
        }
        if (atom instanceof SWRLObjectPropertyAtom o) {
            if (o.getPredicate().isAnonymous()) {
                throw new Unsupported("inverse or complex object property " + o.getPredicate());
            }
            return new Step("object", o.getPredicate().asOWLObjectProperty().getIRI(), null,
                    List.of(iTerm(o.getFirstArgument()), iTerm(o.getSecondArgument())), index);
        }
        if (atom instanceof SWRLDataPropertyAtom d) {
            if (d.getPredicate().isAnonymous()) {
                throw new Unsupported("complex data property " + d.getPredicate());
            }
            return new Step("data", d.getPredicate().asOWLDataProperty().getIRI(), null,
                    List.of(iTerm(d.getFirstArgument()), dTerm(d.getSecondArgument())), index);
        }
        if (atom instanceof SWRLBuiltInAtom b) {
            String name = b.getPredicate().getShortForm();
            if (!isSupportedBuiltin(name)) {
                throw new Unsupported("built-in " + name);
            }
            List<Term> args = new ArrayList<>();
            if (atom instanceof SWRLAPIBuiltInAtom api) {
                for (SWRLBuiltInArgument arg : api.getBuiltInArguments()) {
                    args.add(builtInTerm(arg, name));
                }
            } else {
                for (SWRLDArgument arg : b.getArguments()) {
                    args.add(dTerm(arg));
                }
            }
            return new Step("builtin", b.getPredicate(), name, args, index);
        }
        throw new Unsupported(atom.getClass().getSimpleName().replace("Impl", "").replace("SWRL", "")
                .replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase());
    }

    static boolean isSupportedBuiltin(String name) {
        return COMPARATORS.contains(name) || ARITHMETIC.contains(name) || STRING_TESTS.contains(name)
                || CONCAT.contains(name);
    }

    private static Term iTerm(SWRLIArgument arg) throws Unsupported {
        if (arg instanceof SWRLVariable v) {
            return new Term(v.getIRI().getShortForm(), null);
        }
        if (arg instanceof SWRLIndividualArgument i && i.getIndividual().isNamed()) {
            return new Term(null, i.getIndividual().asOWLNamedIndividual().getIRI());
        }
        throw new Unsupported("anonymous individual argument");
    }

    private static Term dTerm(SWRLDArgument arg) throws Unsupported {
        if (arg instanceof SWRLVariable v) {
            return new Term(v.getIRI().getShortForm(), null);
        }
        if (arg instanceof SWRLLiteralArgument l) {
            return new Term(null, l.getLiteral());
        }
        throw new Unsupported("built-in argument " + arg);
    }

    private static Term builtInTerm(SWRLBuiltInArgument arg, String builtin) throws Unsupported {
        if (arg instanceof SWRLVariableBuiltInArgument v) {
            return new Term(v.getVariableName(), null);
        }
        if (arg instanceof SWRLLiteralBuiltInArgument l) {
            return new Term(null, l.getLiteral());
        }
        if (arg instanceof SWRLNamedIndividualBuiltInArgument n) {
            return new Term(null, n.getIRI());
        }
        throw new Unsupported("argument " + arg + " of built-in " + builtin);
    }

    static String display(Object value) {
        if (value instanceof IRI iri) {
            return iri.getShortForm();
        }
        if (value instanceof OWLLiteral l) {
            return l.getLiteral();
        }
        return String.valueOf(value);
    }

    static boolean valuesEqual(Object a, Object b) {
        if (a instanceof IRI && b instanceof IRI) {
            return a.equals(b);
        }
        if (a instanceof OWLLiteral x && b instanceof OWLLiteral y) {
            BigDecimal nx = number(x);
            BigDecimal ny = number(y);
            if (nx != null && ny != null) {
                return nx.compareTo(ny) == 0;
            }
            return x.getLiteral().equals(y.getLiteral()) && x.getLang().equals(y.getLang());
        }
        return false;
    }

    static BigDecimal number(OWLLiteral literal) {
        String type = literal.getDatatype().getIRI().getShortForm();
        if (!NUMERIC_TYPES.contains(type)) {
            return null;
        }
        try {
            return new BigDecimal(literal.getLiteral().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isInteger(OWLLiteral literal) {
        return INTEGER_TYPES.contains(literal.getDatatype().getIRI().getShortForm()) && number(literal) != null;
    }

    private record Outcome(boolean ok, Object bound, String detail) {
    }

    static final class Solver {
        private final Facts facts;
        private final List<Step> steps;
        private final int maxSolutions;
        final List<Map<String, Object>> solutions = new ArrayList<>();
        final List<boolean[]> solutionDone = new ArrayList<>();
        Failure best;
        int deadEnds;
        int candidates;
        boolean limitHit;

        Solver(Facts facts, List<Step> steps, int maxSolutions) {
            this.facts = facts;
            this.steps = steps;
            this.maxSolutions = maxSolutions;
        }

        void run(Map<String, Object> seed) throws Unsupported {
            solve(new LinkedHashMap<>(seed), new boolean[steps.size()], 0);
        }

        private boolean stop() {
            return limitHit || solutions.size() >= maxSolutions;
        }

        private void solve(Map<String, Object> binding, boolean[] done, int satisfied) throws Unsupported {
            if (stop()) {
                return;
            }
            Step next = null;
            for (Step step : steps) {
                if (!done[step.index()] && step.isBuiltin() && ready(step, binding)) {
                    next = step;
                    break;
                }
            }
            if (next == null) {
                for (Step step : steps) {
                    if (!done[step.index()] && !step.isBuiltin()) {
                        next = step;
                        break;
                    }
                }
            }
            if (next == null) {
                for (Step step : steps) {
                    if (!done[step.index()]) {
                        throw new Unsupported("built-in " + step.builtin() + " has an argument no other atom provides a value for");
                    }
                }
                solutions.add(new LinkedHashMap<>(binding));
                solutionDone.add(done.clone());
                return;
            }
            if (next.isBuiltin()) {
                evaluateBuiltin(next, binding, done, satisfied);
            } else {
                expand(next, binding, done, satisfied);
            }
        }

        private boolean ready(Step step, Map<String, Object> binding) {
            List<Term> args = step.args();
            int from = (ARITHMETIC.contains(step.builtin()) || CONCAT.contains(step.builtin())) ? 1 : 0;
            for (int i = from; i < args.size(); i++) {
                if (args.get(i).isVariable() && !binding.containsKey(args.get(i).variable())) {
                    return false;
                }
            }
            return true;
        }

        private Object value(Term t, Map<String, Object> binding) {
            return t.isVariable() ? binding.get(t.variable()) : t.constant();
        }

        private void evaluateBuiltin(Step step, Map<String, Object> binding, boolean[] done, int satisfied) throws Unsupported {
            List<Object> values = new ArrayList<>();
            for (Term t : step.args()) {
                values.add(value(t, binding));
            }
            Outcome outcome = evaluate(step.builtin(), values);
            if (!outcome.ok()) {
                recordFailure(step, "comparison_failed", outcome.detail(), binding, done, satisfied);
                return;
            }
            boolean[] nextDone = done.clone();
            nextDone[step.index()] = true;
            Map<String, Object> nextBinding = binding;
            if (outcome.bound() != null) {
                nextBinding = new LinkedHashMap<>(binding);
                nextBinding.put(step.args().get(0).variable(), outcome.bound());
            }
            solve(nextBinding, nextDone, satisfied + 1);
        }

        private void expand(Step step, Map<String, Object> binding, boolean[] done, int satisfied) throws Unsupported {
            List<Term> args = step.args();
            Object first = value(args.get(0), binding);
            if ("class".equals(step.kind())) {
                Map<IRI, Boolean> members = facts.classes.getOrDefault(step.predicate(), Map.of());
                if (first != null) {
                    if (members.containsKey(first)) {
                        advance(step, binding, done, satisfied, null, null);
                    } else {
                        recordFailure(step, "no_supporting_fact",
                                display(first) + " is not known to be a " + step.predicate().getShortForm(), binding, done, satisfied);
                    }
                    return;
                }
                if (members.isEmpty()) {
                    recordFailure(step, "no_supporting_fact", "no individual is known to be a " + step.predicate().getShortForm(),
                            binding, done, satisfied);
                    return;
                }
                for (IRI member : new ArrayList<>(members.keySet())) {
                    if (!take()) {
                        return;
                    }
                    advance(step, binding, done, satisfied, args.get(0).variable(), member);
                    if (stop()) {
                        return;
                    }
                }
                return;
            }
            Map<IRI, List<Object[]>> source = "object".equals(step.kind()) ? facts.objects : facts.data;
            List<Object[]> edges = source.getOrDefault(step.predicate(), List.of());
            Object second = value(args.get(1), binding);
            boolean any = false;
            for (Object[] edge : new ArrayList<>(edges)) {
                if (first != null && !edge[0].equals(first)) {
                    continue;
                }
                if (second != null && !valuesEqual(edge[1], second)) {
                    continue;
                }
                any = true;
                if (!take()) {
                    return;
                }
                Map<String, Object> next = new LinkedHashMap<>(binding);
                boolean consistent = true;
                if (first == null) {
                    consistent = bind(next, args.get(0).variable(), edge[0]);
                }
                if (consistent && second == null) {
                    consistent = bind(next, args.get(1).variable(), edge[1]);
                }
                if (!consistent) {
                    continue;
                }
                boolean[] nextDone = done.clone();
                nextDone[step.index()] = true;
                solve(next, nextDone, satisfied + 1);
                if (stop()) {
                    return;
                }
            }
            if (!any) {
                String name = step.predicate().getShortForm();
                String detail;
                if (first != null && second != null) {
                    detail = "no fact " + name + "(" + display(first) + ", " + display(second) + ")";
                } else if (first != null) {
                    detail = display(first) + " has no " + name + " fact";
                } else if (second != null) {
                    detail = "nothing has " + name + " " + display(second);
                } else {
                    detail = "no " + name + " facts exist";
                }
                recordFailure(step, "no_supporting_fact", detail, binding, done, satisfied);
            }
        }

        private boolean bind(Map<String, Object> binding, String variable, Object value) {
            Object existing = binding.get(variable);
            if (existing == null) {
                binding.put(variable, value);
                return true;
            }
            return valuesEqual(existing, value);
        }

        private boolean take() {
            candidates++;
            if (candidates > CANDIDATE_LIMIT) {
                limitHit = true;
                return false;
            }
            return true;
        }

        private void advance(Step step, Map<String, Object> binding, boolean[] done, int satisfied, String variable,
                             Object value) throws Unsupported {
            Map<String, Object> next = binding;
            if (variable != null) {
                next = new LinkedHashMap<>(binding);
                next.put(variable, value);
            }
            boolean[] nextDone = done.clone();
            nextDone[step.index()] = true;
            solve(next, nextDone, satisfied + 1);
        }

        private void recordFailure(Step step, String result, String detail, Map<String, Object> binding, boolean[] done,
                                   int satisfied) {
            deadEnds++;
            if (best == null || satisfied > best.satisfied()) {
                best = new Failure(satisfied, step.index(), result, detail, new LinkedHashMap<>(binding), done.clone());
            }
        }
    }

    private static Outcome evaluate(String name, List<Object> values) {
        if (COMPARATORS.contains(name)) {
            return compare(name, values.get(0), values.get(1));
        }
        if (STRING_TESTS.contains(name)) {
            String a = values.get(0) instanceof OWLLiteral l ? l.getLiteral() : null;
            String b = values.get(1) instanceof OWLLiteral l ? l.getLiteral() : null;
            if (a == null || b == null) {
                return new Outcome(false, null, "contains needs two text values but got " + display(values.get(0)) + " and " + display(values.get(1)));
            }
            return a.contains(b) ? new Outcome(true, null, null)
                    : new Outcome(false, null, "\"" + a + "\" does not contain \"" + b + "\"");
        }
        if (CONCAT.contains(name)) {
            StringBuilder out = new StringBuilder();
            for (int i = 1; i < values.size(); i++) {
                out.append(display(values.get(i)));
            }
            OWLLiteral result = FACTORY.getOWLLiteral(out.toString());
            return finishBinding(values.get(0), result, "\"" + out + "\"");
        }
        return arithmetic(name, values);
    }

    private static Outcome compare(String name, Object a, Object b) {
        int cmp;
        if (a instanceof OWLLiteral x && b instanceof OWLLiteral y) {
            BigDecimal nx = number(x);
            BigDecimal ny = number(y);
            cmp = nx != null && ny != null ? nx.compareTo(ny) : x.getLiteral().compareTo(y.getLiteral());
            if (("equal".equals(name) || "notEqual".equals(name))) {
                cmp = valuesEqual(x, y) ? 0 : (cmp == 0 ? 1 : cmp);
            }
        } else if (a instanceof IRI && b instanceof IRI && ("equal".equals(name) || "notEqual".equals(name))) {
            cmp = a.equals(b) ? 0 : 1;
        } else {
            return new Outcome(false, null, "cannot compare " + display(a) + " with " + display(b));
        }
        boolean ok = switch (name) {
            case "greaterThan" -> cmp > 0;
            case "lessThan" -> cmp < 0;
            case "greaterThanOrEqual" -> cmp >= 0;
            case "lessThanOrEqual" -> cmp <= 0;
            case "equal" -> cmp == 0;
            default -> cmp != 0;
        };
        return ok ? new Outcome(true, null, null)
                : new Outcome(false, null, display(a) + " is not " + name + " " + display(b));
    }

    private static Outcome arithmetic(String name, List<Object> values) {
        List<BigDecimal> inputs = new ArrayList<>();
        boolean allIntegers = true;
        for (int i = 1; i < values.size(); i++) {
            if (!(values.get(i) instanceof OWLLiteral l) || number(l) == null) {
                return new Outcome(false, null, display(values.get(i)) + " is not a number, so " + name + " cannot be computed");
            }
            inputs.add(number(l));
            allIntegers &= isInteger(l);
        }
        if (inputs.size() < 2) {
            return new Outcome(false, null, name + " needs at least two inputs");
        }
        BigDecimal result = inputs.get(0);
        for (int i = 1; i < inputs.size(); i++) {
            BigDecimal next = inputs.get(i);
            switch (name) {
                case "add" -> result = result.add(next);
                case "subtract" -> result = result.subtract(next);
                case "multiply" -> result = result.multiply(next);
                case "divide" -> {
                    if (next.signum() == 0) {
                        return new Outcome(false, null, "division by zero");
                    }
                    result = result.divide(next, MathContext.DECIMAL64);
                    allIntegers = false;
                }
                default -> {
                    if (next.signum() == 0) {
                        return new Outcome(false, null, "modulo by zero");
                    }
                    result = result.remainder(next);
                }
            }
        }
        OWLLiteral literal;
        if (allIntegers) {
            literal = FACTORY.getOWLLiteral(result.toBigInteger().toString(), OWL2Datatype.XSD_INTEGER);
        } else {
            String text = result.stripTrailingZeros().toPlainString();
            literal = FACTORY.getOWLLiteral(text, OWL2Datatype.XSD_DECIMAL);
        }
        return finishBinding(values.get(0), literal, literal.getLiteral());
    }

    private static Outcome finishBinding(Object existing, OWLLiteral result, String shown) {
        if (existing == null) {
            return new Outcome(true, result, null);
        }
        return valuesEqual(existing, result) ? new Outcome(true, null, null)
                : new Outcome(false, null, "computed " + shown + " but the rule expects " + display(existing));
    }

    static String instantiate(Step step, Map<String, Object> binding) {
        if (step.isBuiltin()) {
            List<String> shown = new ArrayList<>();
            for (Term t : step.args()) {
                Object v = t.isVariable() ? binding.get(t.variable()) : t.constant();
                shown.add(v == null ? "?" + t.variable() : display(v));
            }
            String name = step.builtin();
            if (COMPARATORS.contains(name)) {
                return shown.get(0) + " " + name + " " + shown.get(1);
            }
            if (STRING_TESTS.contains(name)) {
                return "\"" + shown.get(0) + "\" contains \"" + shown.get(1) + "\"";
            }
            String op = switch (name) {
                case "add" -> " + ";
                case "subtract" -> " - ";
                case "multiply" -> " * ";
                case "divide" -> " / ";
                case "mod" -> " mod ";
                default -> " concat ";
            };
            return shown.get(0) + " = " + String.join(op, shown.subList(1, shown.size()));
        }
        List<String> shown = new ArrayList<>();
        for (Term t : step.args()) {
            Object v = t.isVariable() ? binding.get(t.variable()) : t.constant();
            shown.add(v == null ? "?" + t.variable() : display(v));
        }
        return step.predicate().getShortForm() + "(" + String.join(", ", shown) + ")";
    }

    static String premiseKind(Step step, Map<String, Object> binding, Facts facts) {
        if (step.isBuiltin()) {
            return COMPARATORS.contains(step.builtin()) || STRING_TESTS.contains(step.builtin()) ? "comparison" : "computed";
        }
        Object a = step.args().get(0).isVariable() ? binding.get(step.args().get(0).variable()) : step.args().get(0).constant();
        boolean asserted;
        if ("class".equals(step.kind())) {
            asserted = facts.isClassFactAsserted(step.predicate(), (IRI) a);
        } else {
            Term t2 = step.args().get(1);
            Object b = t2.isVariable() ? binding.get(t2.variable()) : t2.constant();
            asserted = facts.edgeAsserted("object".equals(step.kind()) ? facts.objects : facts.data, step.predicate(), a, b);
        }
        return asserted ? "asserted fact" : "derived fact";
    }
}
