package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLRule;
import self.research.ontology.common.rules.DisabledRules;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public final class SwrlRuleKeys {

    public static final String PREFIX = "urn:ontocode:rule:";

    private SwrlRuleKeys() {
    }

    public static boolean isKey(String iri) {
        return iri != null && iri.startsWith(PREFIX);
    }

    public static String keyOf(String ruleId) {
        return PREFIX + ruleId;
    }

    public static String idOf(String key) {
        return key.substring(PREFIX.length());
    }

    public static Optional<String> ruleId(SWRLRule rule) {
        for (OWLAnnotation annotation : rule.annotationsAsList()) {
            if (DisabledRules.RULE_ID.equals(annotation.getProperty().getIRI())
                    && annotation.getValue() instanceof OWLLiteral literal && !literal.getLiteral().isBlank()) {
                return Optional.of(literal.getLiteral().trim());
            }
        }
        return Optional.empty();
    }

    public static Optional<String> name(SWRLRule rule) {
        for (OWLAnnotation annotation : rule.annotationsAsList()) {
            if (annotation.getProperty().isLabel() && annotation.getValue() instanceof OWLLiteral literal) {
                return Optional.of(literal.getLiteral());
            }
        }
        return Optional.empty();
    }

    public static Set<String> keysIn(OWLOntology ontology) {
        Set<String> keys = new LinkedHashSet<>();
        ontology.axioms(AxiomType.SWRL_RULE).forEach(rule -> ruleId(rule).ifPresent(id -> keys.add(keyOf(id))));
        return keys;
    }

    public static Set<SWRLRule> rulesFor(OWLOntology ontology, String key) {
        String id = idOf(key);
        Set<SWRLRule> found = new LinkedHashSet<>();
        ontology.axioms(AxiomType.SWRL_RULE).forEach(rule -> {
            if (ruleId(rule).filter(id::equals).isPresent()) {
                found.add(rule);
            }
        });
        return found;
    }
}
