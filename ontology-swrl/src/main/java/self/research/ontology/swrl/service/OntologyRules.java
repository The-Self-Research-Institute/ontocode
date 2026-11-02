package self.research.ontology.swrl.service;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLAtom;
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
        private String ruleText;

        Entry(String id, String name, String engineName, String comment, String category, boolean enabled,
              String source, SWRLRule axiom) {
            this.id = id;
            this.name = name;
            this.engineName = engineName;
            this.comment = comment;
            this.category = category;
            this.enabled = enabled;
            this.source = source;
            this.axiom = axiom;
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

    private record Raw(SWRLRule axiom, String id, String name, String source) {
    }

    public static List<Entry> collect(OWLOntology root) {
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
            rules.sort(java.util.Comparator.comparing(r -> r.getAxiomWithoutAnnotations().toString()));
            for (SWRLRule rule : rules) {
                if (isSqwrlQuery(rule)) {
                    continue;
                }
                String explicit = annotation(rule, RULE_ID);
                String id = explicit != null && !explicit.isBlank() ? explicit.trim() : stableId(rule);
                if (!seenIds.add(id)) {
                    continue;
                }
                String label = label(rule);
                String name = label != null ? label : "Rule " + stableId(rule).substring(5, 13);
                raws.add(new Raw(rule, id, name, source));
            }
        }
        Map<String, Integer> counts = new HashMap<>();
        raws.forEach(raw -> counts.merge(raw.name(), 1, Integer::sum));
        List<Entry> entries = new ArrayList<>();
        for (Raw raw : raws) {
            String engineName = counts.get(raw.name()) > 1 ? raw.name() + " (" + raw.id() + ")" : raw.name();
            entries.add(new Entry(raw.id(), raw.name(), engineName, comment(raw.axiom()), annotation(raw.axiom(), CATEGORY),
                    !isDisabled(raw.axiom()), raw.source(), raw.axiom()));
        }
        return entries;
    }

    public static List<Entry> prepare(OWLOntology root, Predicate<Entry> active) {
        List<Entry> entries = collect(root);
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
            Set<OWLAnnotation> annotations = new LinkedHashSet<>();
            annotations.add(factory.getOWLAnnotation(factory.getRDFSLabel(), factory.getOWLLiteral(entry.engineName())));
            if (entry.comment() != null && !entry.comment().isEmpty()) {
                annotations.add(factory.getOWLAnnotation(factory.getRDFSComment(), factory.getOWLLiteral(entry.comment())));
            }
            manager.addAxiom(root, entry.axiom().getAxiomWithoutAnnotations().getAnnotatedAxiom(annotations));
        }
        return entries;
    }

    public static void removeAll(OWLOntology root) {
        prepare(root, entry -> false);
    }

    public static void fillTexts(SWRLRuleEngine engine, List<Entry> entries) {
        SWRLRuleRenderer renderer = engine.createSWRLRuleRenderer();
        Map<String, String> byName = new HashMap<>();
        for (SWRLAPIRule rule : engine.getSWRLRules()) {
            if (!rule.isSQWRLQuery()) {
                byName.put(rule.getRuleName(), renderer.renderSWRLRule(rule));
            }
        }
        for (Entry entry : entries) {
            entry.ruleText = byName.get(entry.engineName());
        }
    }
}
