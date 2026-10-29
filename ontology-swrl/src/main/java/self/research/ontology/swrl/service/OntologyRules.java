package self.research.ontology.swrl.service;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.SWRLArgument;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLClassAtom;
import org.semanticweb.owlapi.model.SWRLDArgument;
import org.semanticweb.owlapi.model.SWRLDataPropertyAtom;
import org.semanticweb.owlapi.model.SWRLDataRangeAtom;
import org.semanticweb.owlapi.model.SWRLDifferentIndividualsAtom;
import org.semanticweb.owlapi.model.SWRLIndividualArgument;
import org.semanticweb.owlapi.model.SWRLLiteralArgument;
import org.semanticweb.owlapi.model.SWRLObjectPropertyAtom;
import org.semanticweb.owlapi.model.SWRLSameIndividualAtom;
import org.semanticweb.owlapi.model.SWRLVariable;
import org.semanticweb.owlapi.model.SWRLBuiltInAtom;
import org.semanticweb.owlapi.model.SWRLRule;
import org.swrlapi.core.SWRLAPIRule;
import org.swrlapi.core.SWRLRuleEngine;
import org.swrlapi.core.SWRLRuleRenderer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class OntologyRules {

    public static final String NAMESPACE = "http://ontocode.org/ns#";
    public static final IRI ENABLED = IRI.create(NAMESPACE + "enabled");
    public static final IRI CATEGORY = IRI.create(NAMESPACE + "category");
    public static final IRI RULE_ID = IRI.create(NAMESPACE + "ruleId");
    public static final IRI PROTEGE_ENABLED = IRI.create("http://swrl.stanford.edu/ontologies/3.3/swrla.owl#isRuleEnabled");

    private static final String SQWRL_BUILT_INS = "http://sqwrl.stanford.edu/ontologies/built-ins/";

    private OntologyRules() {
    }

    public static final class Entry {
        private final String id;
        private final String name;
        private final String engineName;
        private final String comment;
        private final String category;
        private final boolean enabled;
        private final String source;
        private final SWRLRule axiom;
        private final boolean query;
        private String ruleText;
        private boolean textFromFallback;

        Entry(String id, String name, String engineName, String comment, String category, boolean enabled,
              String source, SWRLRule axiom, boolean query) {
            this.id = id;
            this.name = name;
            this.engineName = engineName;
            this.comment = comment;
            this.category = category;
            this.enabled = enabled;
            this.source = source;
            this.axiom = axiom;
            this.query = query;
        }

        public String id() { return id; }
        public String name() { return name; }
        public String engineName() { return engineName; }
        public String comment() { return comment; }
        public String category() { return category; }
        public boolean enabled() { return enabled; }
        public String source() { return source; }
        public boolean readOnly() { return source != null; }
        public SWRLRule axiom() { return axiom; }
        public String ruleText() { return ruleText; }
        public boolean query() { return query; }
        public boolean textFromFallback() { return textFromFallback; }
    }

    public static boolean isSqwrlQuery(SWRLRule rule) {
        for (SWRLAtom atom : rule.getBody()) {
            if (atom instanceof SWRLBuiltInAtom builtIn && builtIn.getPredicate().toString().startsWith(SQWRL_BUILT_INS)) {
                return true;
            }
        }
        for (SWRLAtom atom : rule.getHead()) {
            if (atom instanceof SWRLBuiltInAtom builtIn && builtIn.getPredicate().toString().startsWith(SQWRL_BUILT_INS)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isDisabled(SWRLRule rule) {
        String value = annotation(rule, ENABLED);
        if (value == null) {
            value = annotation(rule, PROTEGE_ENABLED);
        }
        if (value == null) {
            return false;
        }
        String text = value.trim();
        return "false".equalsIgnoreCase(text) || "0".equals(text);
    }

    public static String stableId(SWRLRule rule) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(rule.getAxiomWithoutAnnotations().toString().getBytes(StandardCharsets.UTF_8));
            return "rule-" + HexFormat.of().formatHex(bytes, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String annotation(SWRLRule rule, IRI property) {
        for (OWLAnnotation annotation : rule.getAnnotations()) {
            if (property.equals(annotation.getProperty().getIRI())) {
                com.google.common.base.Optional<OWLLiteral> literal = annotation.getValue().asLiteral();
                if (literal.isPresent()) {
                    return literal.get().getLiteral();
                }
            }
        }
        return null;
    }

    private static String label(SWRLRule rule) {
        for (OWLAnnotation annotation : rule.getAnnotations()) {
            if (annotation.getProperty().isLabel()) {
                com.google.common.base.Optional<OWLLiteral> literal = annotation.getValue().asLiteral();
                if (literal.isPresent() && !literal.get().getLiteral().isBlank()) {
                    return literal.get().getLiteral();
                }
            }
        }
        return null;
    }

    private static String comment(SWRLRule rule) {
        for (OWLAnnotation annotation : rule.getAnnotations()) {
            if (annotation.getProperty().isComment()) {
                com.google.common.base.Optional<OWLLiteral> literal = annotation.getValue().asLiteral();
                if (literal.isPresent()) {
                    return literal.get().getLiteral();
                }
            }
        }
        return null;
    }

    private record Raw(SWRLRule axiom, String id, String name, String source, boolean query) {
    }

    public static List<Entry> collect(OWLOntology root) {
        return collect(root, false);
    }

    public static List<Entry> collect(OWLOntology root, boolean includeQueries) {
        List<OWLOntology> order = new ArrayList<>();
        order.add(root);
        for (OWLOntology member : root.getImportsClosure()) {
            if (member != root) {
                order.add(member);
            }
        }
        List<Raw> raws = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (OWLOntology member : order) {
            String source = null;
            if (member != root) {
                com.google.common.base.Optional<IRI> iri = member.getOntologyID().getOntologyIRI();
                source = iri.isPresent() ? iri.get().toString() : member.getOntologyID().toString();
            }
            List<SWRLRule> rules = new ArrayList<>(member.getAxioms(AxiomType.SWRL_RULE));
            rules.sort(java.util.Comparator.comparing((SWRLRule r) -> r.getAxiomWithoutAnnotations().toString())
                    .thenComparing(SWRLRule::toString));
            Set<String> seenInMember = new HashSet<>();
            for (SWRLRule rule : rules) {
                boolean query = isSqwrlQuery(rule);
                if (query && !includeQueries) {
                    continue;
                }
                String explicit = annotation(rule, RULE_ID);
                String id = explicit != null && !explicit.isBlank() ? explicit.trim() : stableId(rule);
                if (!seenInMember.add(id)) {
                    String base = id;
                    int copy = 2;
                    while (!seenInMember.add(id = base + "-" + copy)) {
                        copy++;
                    }
                }
                if (!seenIds.add(id)) {
                    continue;
                }
                String label = label(rule);
                String name = label != null ? label : "Rule " + stableId(rule).substring(5, 13);
                raws.add(new Raw(rule, id, name, source, query));
            }
        }
        Map<String, Integer> counts = new HashMap<>();
        raws.forEach(raw -> counts.merge(raw.name(), 1, Integer::sum));
        List<Entry> entries = new ArrayList<>();
        for (Raw raw : raws) {
            String engineName = counts.get(raw.name()) > 1 ? raw.name() + " (" + raw.id() + ")" : raw.name();
            entries.add(new Entry(raw.id(), raw.name(), engineName, comment(raw.axiom()), annotation(raw.axiom(), CATEGORY),
                    !raw.query() && !isDisabled(raw.axiom()), raw.source(), raw.axiom(), raw.query()));
        }
        return entries;
    }

    public static List<Entry> prepare(OWLOntology root, Predicate<Entry> active) {
        return prepare(root, active, false);
    }

    public static List<Entry> prepare(OWLOntology root, Predicate<Entry> active, boolean includeQueries) {
        List<Entry> entries = collect(root, includeQueries);
        OWLOntologyManager manager = root.getOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        for (OWLOntology member : root.getImportsClosure()) {
            Set<SWRLRule> present = new HashSet<>(member.getAxioms(AxiomType.SWRL_RULE));
            if (!present.isEmpty()) {
                manager.removeAxioms(member, present);
            }
        }
        for (Entry entry : entries) {
            if (!active.test(entry)) {
                continue;
            }
            manager.addAxiom(root, engineAxiom(factory, entry));
        }
        return entries;
    }

    private static SWRLRule engineAxiom(OWLDataFactory factory, Entry entry) {
        Set<OWLAnnotation> annotations = new LinkedHashSet<>();
        annotations.add(factory.getOWLAnnotation(factory.getRDFSLabel(), factory.getOWLLiteral(entry.engineName())));
        if (entry.comment() != null && !entry.comment().isEmpty()) {
            annotations.add(factory.getOWLAnnotation(factory.getRDFSComment(), factory.getOWLLiteral(entry.comment())));
        }
        return (SWRLRule) entry.axiom().getAxiomWithoutAnnotations().getAnnotatedAxiom(annotations);
    }

    public static void drop(OWLOntology root, Entry entry) {
        OWLOntologyManager manager = root.getOWLOntologyManager();
        manager.removeAxiom(root, engineAxiom(manager.getOWLDataFactory(), entry));
    }

    public static void removeAll(OWLOntology root) {
        prepare(root, entry -> false);
    }

    public static void fillTexts(SWRLRuleEngine engine, List<Entry> entries) {
        SWRLRuleRenderer renderer = engine.createSWRLRuleRenderer();
        Map<String, String> byName = new HashMap<>();
        for (SWRLAPIRule rule : engine.getSWRLRules()) {
            byName.put(rule.getRuleName(), renderer.renderSWRLRule(rule));
        }
        for (Entry entry : entries) {
            entry.ruleText = byName.get(entry.engineName());
        }
    }

    public static void fillFallbackTexts(List<Entry> entries) {
        for (Entry entry : entries) {
            if (entry.ruleText == null) {
                entry.ruleText = fallbackText(entry.axiom);
                entry.textFromFallback = true;
            }
        }
    }

    static String fallbackText(SWRLRule rule) {
        return atoms(rule.getBody()) + " -> " + atoms(rule.getHead());
    }

    private static String atoms(Set<SWRLAtom> atoms) {
        List<String> parts = new ArrayList<>();
        for (SWRLAtom atom : atoms) {
            parts.add(atomText(atom));
        }
        return String.join(" ^ ", parts);
    }

    private static String atomText(SWRLAtom atom) {
        if (atom instanceof SWRLClassAtom classAtom) {
            return term(classAtom.getPredicate()) + "(" + argument(classAtom.getArgument()) + ")";
        }
        if (atom instanceof SWRLObjectPropertyAtom property) {
            return term(property.getPredicate()) + "(" + argument(property.getFirstArgument()) + ", " + argument(property.getSecondArgument()) + ")";
        }
        if (atom instanceof SWRLDataPropertyAtom property) {
            return term(property.getPredicate()) + "(" + argument(property.getFirstArgument()) + ", " + argument(property.getSecondArgument()) + ")";
        }
        if (atom instanceof SWRLBuiltInAtom builtIn) {
            List<String> arguments = new ArrayList<>();
            for (SWRLDArgument argument : builtIn.getArguments()) {
                arguments.add(argument(argument));
            }
            String name = shortName(builtIn.getPredicate().toString());
            String prefix = builtIn.getPredicate().toString().contains("swrlb#") ? "swrlb:" : "";
            return prefix + name + "(" + String.join(", ", arguments) + ")";
        }
        if (atom instanceof SWRLSameIndividualAtom same) {
            return "sameAs(" + argument(same.getFirstArgument()) + ", " + argument(same.getSecondArgument()) + ")";
        }
        if (atom instanceof SWRLDifferentIndividualsAtom different) {
            return "differentFrom(" + argument(different.getFirstArgument()) + ", " + argument(different.getSecondArgument()) + ")";
        }
        if (atom instanceof SWRLDataRangeAtom range) {
            return range.getPredicate().toString() + "(" + argument(range.getArgument()) + ")";
        }
        return atom.toString();
    }

    private static String term(OWLObject expression) {
        if (expression instanceof OWLEntity entity) {
            return shortName(entity.getIRI().toString());
        }
        return expression.toString();
    }

    private static String argument(SWRLArgument argument) {
        if (argument instanceof SWRLVariable variable) {
            return "?" + shortName(variable.getIRI().toString());
        }
        if (argument instanceof SWRLIndividualArgument individual) {
            OWLIndividual value = individual.getIndividual();
            return value.isNamed() ? shortName(value.asOWLNamedIndividual().getIRI().toString()) : value.toString();
        }
        if (argument instanceof SWRLLiteralArgument literal) {
            OWLLiteral value = literal.getLiteral();
            return value.getDatatype().isString() || value.isRDFPlainLiteral()
                    ? "\"" + value.getLiteral() + "\"" : value.getLiteral();
        }
        return argument.toString();
    }

    private static String shortName(String iri) {
        int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return cut >= 0 && cut < iri.length() - 1 ? iri.substring(cut + 1) : iri;
    }
}
