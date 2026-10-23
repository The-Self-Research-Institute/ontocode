package self.research.ontology.common;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLBuiltInAtom;
import org.semanticweb.owlapi.model.SWRLRule;

import java.util.stream.Stream;

public final class SwrlBuiltIns {

    private SwrlBuiltIns() {
    }

    public static long countBuiltInRules(OWLOntology ontology) {
        return ontology.axioms(AxiomType.SWRL_RULE).filter(SwrlBuiltIns::usesBuiltIn).count();
    }

    public static OWLOntology withoutBuiltInRules(OWLOntology ontology) {
        if (countBuiltInRules(ontology) == 0) {
            return ontology;
        }
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology copy = manager.createOntology();
            manager.addAxioms(copy, ontology.axioms()
                    .filter(axiom -> !(axiom instanceof SWRLRule rule && usesBuiltIn(rule))));
            return copy;
        } catch (OWLOntologyCreationException e) {
            throw new IllegalStateException("Could not copy the ontology without its SWRL built-in rules", e);
        }
    }

    public static String omissionNote(long count) {
        return "HermiT does not support SWRL built-in functions, so " + count + " rule" + (count == 1 ? "" : "s")
                + " using them " + (count == 1 ? "was" : "were") + " left out of reasoning.";
    }

    private static boolean usesBuiltIn(SWRLRule rule) {
        return Stream.concat(rule.body(), rule.head()).anyMatch(SWRLBuiltInAtom.class::isInstance);
    }
}
