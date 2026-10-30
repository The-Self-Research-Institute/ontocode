package self.research.ontology.common.rules;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLBuiltInAtom;
import org.semanticweb.owlapi.model.SWRLRule;

import java.util.List;

public final class DisabledRules {

    public static final String NAMESPACE = "http://ontocode.org/ns#";
    public static final IRI ENABLED = IRI.create(NAMESPACE + "enabled");
    public static final IRI CATEGORY = IRI.create(NAMESPACE + "category");
    public static final IRI RULE_ID = IRI.create(NAMESPACE + "ruleId");
    public static final IRI SWRLA_ENABLED = IRI.create("http://swrl.stanford.edu/ontologies/3.3/swrla.owl#isRuleEnabled");

    private static final String SQWRL_BUILT_INS = "http://sqwrl.stanford.edu/ontologies/built-ins/";

    private DisabledRules() {
    }

    public static boolean isQuery(OWLAxiom axiom) {
        if (!(axiom instanceof SWRLRule rule)) {
            return false;
        }
        return rule.body().anyMatch(DisabledRules::isSqwrlAtom) || rule.head().anyMatch(DisabledRules::isSqwrlAtom);
    }

    private static boolean isSqwrlAtom(SWRLAtom atom) {
        return atom instanceof SWRLBuiltInAtom builtIn && builtIn.getPredicate().toString().startsWith(SQWRL_BUILT_INS);
    }

    public static boolean isExcluded(OWLAxiom axiom) {
        return isDisabled(axiom) || isQuery(axiom);
    }

    public static boolean isDisabled(OWLAxiom axiom) {
        if (!(axiom instanceof SWRLRule)) {
            return false;
        }
        Boolean own = flag(axiom, ENABLED);
        if (own != null) {
            return !own;
        }
        Boolean protege = flag(axiom, SWRLA_ENABLED);
        return protege != null && !protege;
    }

    public static Boolean flag(OWLAxiom axiom, IRI property) {
        for (OWLAnnotation annotation : axiom.annotationsAsList()) {
            if (property.equals(annotation.getProperty().getIRI()) && annotation.getValue() instanceof OWLLiteral literal) {
                String text = literal.getLiteral().trim();
                return !("false".equalsIgnoreCase(text) || "0".equals(text));
            }
        }
        return null;
    }

    public static int strip(OWLOntology ontology) {
        if (ontology == null) {
            return 0;
        }
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        int removed = 0;
        List<OWLOntology> closure = ontology.importsClosure().toList();
        for (OWLOntology member : closure) {
            List<SWRLRule> disabled = member.axioms(AxiomType.SWRL_RULE).filter(DisabledRules::isExcluded).toList();
            if (!disabled.isEmpty()) {
                manager.removeAxioms(member, disabled.stream());
                removed += disabled.size();
            }
        }
        return removed;
    }
}
