package self.research.ontology.owlEditor.hierarchy;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * ontology statistics from an in-memory OWLAPI model + structural reasoner.
 */
@Component
public class OntologyMetricsComputer {

    private static final Logger log = LoggerFactory.getLogger(OntologyMetricsComputer.class);

    /** Fast-open: signature counts from parsed ontology only (no reasoner). */
    public Map<String, Object> computeAsserted(OWLOntology ontology) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        Imports imp = Imports.INCLUDED;

        metrics.put("classCount", ontology.classesInSignature(imp).filter(c -> !c.isBuiltIn()).count());
        metrics.put("objectPropertyCount", ontology.objectPropertiesInSignature(imp).filter(p -> !p.isBuiltIn()).count());
        metrics.put("dataPropertyCount", ontology.dataPropertiesInSignature(imp).filter(p -> !p.isBuiltIn()).count());
        metrics.put("individualCount", ontology.individualsInSignature(imp).filter(i -> !i.isBuiltIn()).count());
        metrics.put("annotationPropertyCount", ontology.annotationPropertiesInSignature(imp).filter(p -> !p.isBuiltIn()).count());
        metrics.put("axiomCount", ontology.getAxiomCount());
        metrics.put("logicalAxiomCount", ontology.getLogicalAxiomCount());
        metrics.put("hiddenGciCount", 0);
        metrics.put("hierarchyEngine", "owlapi-asserted");
        return metrics;
    }

    public Map<String, Object> compute(OWLOntology ontology, OWLReasoner reasoner) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        Imports imp = Imports.INCLUDED;

        metrics.put("classCount", ontology.classesInSignature(imp).filter(c -> !c.isBuiltIn()).count());
        metrics.put("objectPropertyCount", ontology.objectPropertiesInSignature(imp).filter(p -> !p.isBuiltIn()).count());
        metrics.put("dataPropertyCount", ontology.dataPropertiesInSignature(imp).filter(p -> !p.isBuiltIn()).count());
        metrics.put("individualCount", ontology.individualsInSignature(imp).filter(i -> !i.isBuiltIn()).count());
        metrics.put("annotationPropertyCount", ontology.annotationPropertiesInSignature(imp).filter(p -> !p.isBuiltIn()).count());

        metrics.put("axiomCount", ontology.getAxiomCount());
        metrics.put("logicalAxiomCount", ontology.getLogicalAxiomCount());
        metrics.put("declarationAxiomCount", ontology.getAxiomCount(AxiomType.DECLARATION));
        metrics.put("subClassOfAxiomCount", ontology.getAxiomCount(AxiomType.SUBCLASS_OF));
        metrics.put("equivalentClassesAxiomCount", ontology.getAxiomCount(AxiomType.EQUIVALENT_CLASSES));
        metrics.put("disjointClassesAxiomCount", ontology.getAxiomCount(AxiomType.DISJOINT_CLASSES));

        long gciCount = ontology.getAxioms(AxiomType.SUBCLASS_OF).stream()
                .filter(ax -> ax.getSubClass().isAnonymous())
                .count();
        metrics.put("gciCount", (int) gciCount);
        metrics.put("hiddenGciCount", computeHiddenGciCount(ontology, reasoner));

        return metrics;
    }

    /**
     * Entailed anonymous SubClassOf axioms (direct) not present in the asserted ontology.
     * Matches "hidden GCI" using the structural reasoner already loaded for hierarchy.
     */
    private int computeHiddenGciCount(OWLOntology ontology, OWLReasoner reasoner) {
        try {
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            Set<String> assertedKeys = new HashSet<>();
            ontology.getAxioms(AxiomType.SUBCLASS_OF).stream()
                    .filter(ax -> ax.getSubClass().isAnonymous())
                    .forEach(ax -> assertedKeys.add(axiomKey(ax)));

            Set<String> entailedKeys = new HashSet<>();
            collectEntailedAnonymousGcis(reasoner, df, df.getOWLThing(), entailedKeys);
            for (OWLClass named : ontology.getClassesInSignature(Imports.INCLUDED)) {
                if (named.isBuiltIn() || named.isAnonymous()) {
                    continue;
                }
                collectEntailedAnonymousGcis(reasoner, df, named, entailedKeys);
            }

            int hidden = 0;
            for (String key : entailedKeys) {
                if (!assertedKeys.contains(key)) {
                    hidden++;
                }
            }
            return hidden;
        } catch (Exception e) {
            log.warn("Hidden GCI computation failed (using 0): {}", e.getMessage());
            return 0;
        }
    }

    private void collectEntailedAnonymousGcis(OWLReasoner reasoner, OWLDataFactory df,
                                            OWLClass parent, Set<String> keys) {
        for (Node<OWLClass> node : reasoner.getSubClasses(parent, true)) {
            for (OWLClass sub : node) {
                if (sub.isAnonymous() && !sub.isOWLNothing()) {
                    keys.add(axiomKey(df.getOWLSubClassOfAxiom(sub, parent)));
                }
            }
        }
    }

    private static String axiomKey(OWLSubClassOfAxiom ax) {
        return ax.getSubClass().toString() + " ==> " + ax.getSuperClass().toString();
    }
}
