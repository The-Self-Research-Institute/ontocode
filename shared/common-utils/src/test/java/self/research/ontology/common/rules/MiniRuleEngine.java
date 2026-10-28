package self.research.ontology.common.rules;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLBuiltInAtom;
import org.semanticweb.owlapi.model.SWRLClassAtom;
import org.semanticweb.owlapi.model.SWRLDArgument;
import org.semanticweb.owlapi.model.SWRLDataPropertyAtom;
import org.semanticweb.owlapi.model.SWRLIArgument;
import org.semanticweb.owlapi.model.SWRLIndividualArgument;
import org.semanticweb.owlapi.model.SWRLLiteralArgument;
import org.semanticweb.owlapi.model.SWRLObjectPropertyAtom;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.model.SWRLVariable;
import self.research.ontology.common.RuleStage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

class MiniRuleEngine implements RuleStage {

    final AtomicInteger calls = new AtomicInteger();
    private final OWLDataFactory df = OWLManager.getOWLDataFactory();
    private final int passes;
    private final Function<Integer, Boolean> available;

    MiniRuleEngine() {
        this(50, call -> true);
    }

    MiniRuleEngine(int passes, Function<Integer, Boolean> available) {
        this.passes = passes;
        this.available = available;
    }

    static MiniRuleEngine down() {
        return new MiniRuleEngine(50, call -> false);
    }

    @Override
    public Optional<Set<OWLAxiom>> infer(OWLOntology ontology, Collection<SWRLRule> rules) {
        int call = calls.incrementAndGet();
        if (!available.apply(call)) {
            return Optional.empty();
        }
        Set<OWLAxiom> facts = new LinkedHashSet<>();
        ontology.axioms().filter(a -> a instanceof OWLClassAssertionAxiom || a instanceof OWLObjectPropertyAssertionAxiom
                || a instanceof OWLDataPropertyAssertionAxiom).forEach(facts::add);
        Set<OWLAxiom> produced = new LinkedHashSet<>();
        for (int pass = 0; pass < passes; pass++) {
            Set<OWLAxiom> fresh = new LinkedHashSet<>();
            for (SWRLRule rule : rules) {
                for (Map<IRI, Object> binding : match(new ArrayList<>(rule.body().toList()), 0, new HashMap<>(), facts)) {
                    rule.head().forEach(atom -> instantiate(atom, binding).ifPresent(axiom -> {
                        if (!facts.contains(axiom) && !produced.contains(axiom)) {
                            fresh.add(axiom);
                        }
                    }));
                }
            }
            if (fresh.isEmpty()) {
                break;
            }
            facts.addAll(fresh);
            produced.addAll(fresh);
        }
        return Optional.of(produced);
    }

    private List<Map<IRI, Object>> match(List<SWRLAtom> body, int index, Map<IRI, Object> binding, Set<OWLAxiom> facts) {
        if (index == body.size()) {
            return List.of(binding);
        }
        List<Map<IRI, Object>> out = new ArrayList<>();
        SWRLAtom atom = body.get(index);
        if (atom instanceof SWRLClassAtom c) {
            for (OWLAxiom f : facts) {
                if (f instanceof OWLClassAssertionAxiom a && a.getClassExpression().equals(c.getPredicate())) {
                    bind(c.getArgument(), a.getIndividual(), binding).ifPresent(b -> out.addAll(match(body, index + 1, b, facts)));
                }
            }
        } else if (atom instanceof SWRLObjectPropertyAtom p) {
            for (OWLAxiom f : facts) {
                if (f instanceof OWLObjectPropertyAssertionAxiom a && a.getProperty().equals(p.getPredicate())) {
                    bind(p.getFirstArgument(), a.getSubject(), binding)
                            .flatMap(b -> bind(p.getSecondArgument(), a.getObject(), b))
                            .ifPresent(b -> out.addAll(match(body, index + 1, b, facts)));
                }
            }
        } else if (atom instanceof SWRLDataPropertyAtom p) {
            for (OWLAxiom f : facts) {
                if (f instanceof OWLDataPropertyAssertionAxiom a && a.getProperty().equals(p.getPredicate())) {
                    bind(p.getFirstArgument(), a.getSubject(), binding)
                            .flatMap(b -> bindData(p.getSecondArgument(), a.getObject(), b))
                            .ifPresent(b -> out.addAll(match(body, index + 1, b, facts)));
                }
            }
        } else if (atom instanceof SWRLBuiltInAtom builtIn) {
            builtIn(builtIn, binding).ifPresent(b -> out.addAll(match(body, index + 1, b, facts)));
        }
        return out;
    }

    private Optional<Map<IRI, Object>> builtIn(SWRLBuiltInAtom atom, Map<IRI, Object> binding) {
        String name = atom.getPredicate().getFragment();
        List<SWRLDArgument> args = atom.getArguments();
        switch (name) {
            case "add": {
                Double left = number(args.get(1), binding);
                Double right = number(args.get(2), binding);
                if (left == null || right == null) {
                    return Optional.empty();
                }
                return bindData(args.get(0), df.getOWLLiteral((int) (left + right)), binding);
            }
            case "greaterThan": {
                Double left = number(args.get(0), binding);
                Double right = number(args.get(1), binding);
                return left != null && right != null && left > right ? Optional.of(binding) : Optional.empty();
            }
            case "lessThan": {
                Double left = number(args.get(0), binding);
                Double right = number(args.get(1), binding);
                return left != null && right != null && left < right ? Optional.of(binding) : Optional.empty();
            }
            default:
                return Optional.empty();
        }
    }

    private Double number(SWRLDArgument arg, Map<IRI, Object> binding) {
        Object value = arg instanceof SWRLVariable v ? binding.get(v.getIRI()) : arg instanceof SWRLLiteralArgument l ? l.getLiteral() : null;
        if (value instanceof OWLLiteral literal) {
            try {
                return Double.parseDouble(literal.getLiteral());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Optional<Map<IRI, Object>> bind(SWRLIArgument arg, Object value, Map<IRI, Object> binding) {
        if (arg instanceof SWRLVariable v) {
            Object existing = binding.get(v.getIRI());
            if (existing != null) {
                return existing.equals(value) ? Optional.of(binding) : Optional.empty();
            }
            Map<IRI, Object> next = new HashMap<>(binding);
            next.put(v.getIRI(), value);
            return Optional.of(next);
        }
        return arg instanceof SWRLIndividualArgument i && i.getIndividual().equals(value) ? Optional.of(binding) : Optional.empty();
    }

    private Optional<Map<IRI, Object>> bindData(SWRLDArgument arg, OWLLiteral value, Map<IRI, Object> binding) {
        if (arg instanceof SWRLVariable v) {
            Object existing = binding.get(v.getIRI());
            if (existing != null) {
                return existing.equals(value) ? Optional.of(binding) : Optional.empty();
            }
            Map<IRI, Object> next = new HashMap<>(binding);
            next.put(v.getIRI(), value);
            return Optional.of(next);
        }
        return arg instanceof SWRLLiteralArgument l && l.getLiteral().equals(value) ? Optional.of(binding) : Optional.empty();
    }

    private Optional<OWLAxiom> instantiate(SWRLAtom atom, Map<IRI, Object> binding) {
        if (atom instanceof SWRLClassAtom c && c.getArgument() instanceof SWRLVariable v && binding.get(v.getIRI()) instanceof org.semanticweb.owlapi.model.OWLIndividual i) {
            return Optional.of(df.getOWLClassAssertionAxiom(c.getPredicate(), i));
        }
        if (atom instanceof SWRLObjectPropertyAtom p && p.getFirstArgument() instanceof SWRLVariable a && p.getSecondArgument() instanceof SWRLVariable b
                && binding.get(a.getIRI()) instanceof org.semanticweb.owlapi.model.OWLIndividual x
                && binding.get(b.getIRI()) instanceof org.semanticweb.owlapi.model.OWLIndividual y) {
            return Optional.of(df.getOWLObjectPropertyAssertionAxiom(p.getPredicate(), x, y));
        }
        if (atom instanceof SWRLDataPropertyAtom p && p.getFirstArgument() instanceof SWRLVariable a && p.getSecondArgument() instanceof SWRLVariable b
                && binding.get(a.getIRI()) instanceof org.semanticweb.owlapi.model.OWLIndividual x && binding.get(b.getIRI()) instanceof OWLLiteral l) {
            return Optional.of(df.getOWLDataPropertyAssertionAxiom(p.getPredicate(), x, l));
        }
        return Optional.empty();
    }

    @Override
    public String describe() {
        return "mini test engine";
    }
}
