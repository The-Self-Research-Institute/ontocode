package self.research.ontology.common;

import org.semanticweb.owlapi.model.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public final class JustificationRendering {

    private JustificationRendering() {}

    private static final Set<AxiomType<?>> CONFLICT_AXIOM_TYPES = Set.of(
        AxiomType.DISJOINT_CLASSES,
        AxiomType.DISJOINT_UNION,
        AxiomType.DISJOINT_OBJECT_PROPERTIES,
        AxiomType.DISJOINT_DATA_PROPERTIES,
        AxiomType.IRREFLEXIVE_OBJECT_PROPERTY,
        AxiomType.ASYMMETRIC_OBJECT_PROPERTY
    );

    public static boolean isConflictAxiom(OWLAxiom axiom) {
        if (CONFLICT_AXIOM_TYPES.contains(axiom.getAxiomType())) {
            return true;
        }
        for (OWLClassExpression ce : axiom.getNestedClassExpressions()) {
            if (ce instanceof OWLObjectComplementOf) {
                return true;
            }
        }
        return false;
    }

    public static String entityKind(OWLEntity entity) {
        if (entity.isOWLClass()) return "class";
        if (entity.isOWLNamedIndividual()) return "individual";
        if (entity.isOWLObjectProperty()) return "objectProperty";
        if (entity.isOWLDataProperty()) return "dataProperty";
        if (entity.isOWLAnnotationProperty()) return "annotationProperty";
        if (entity.isOWLDatatype()) return "datatype";
        return "entity";
    }

    public static List<Map<String, Object>> extractEntities(
            OWLAxiom axiom, OWLOntology ontology, BiFunction<OWLEntity, OWLOntology, String> labelResolver) {
        List<Map<String, Object>> entities = new ArrayList<>();
        for (OWLEntity entity : axiom.getSignature()) {
            if (entity.isTopEntity() || entity.isBottomEntity()) {
                continue;
            }
            Map<String, Object> entry = new HashMap<>();
            entry.put("iri", entity.getIRI().toString());
            entry.put("label", labelResolver.apply(entity, ontology));
            entry.put("kind", entityKind(entity));
            entities.add(entry);
        }
        return entities;
    }

    public static Map<String, Object> describeEntailment(OWLAxiom axiom) {
        if (axiom instanceof OWLSubClassOfAxiom) {
            OWLSubClassOfAxiom ax = (OWLSubClassOfAxiom) axiom;
            if (!ax.getSubClass().isAnonymous() && !ax.getSuperClass().isAnonymous()) {
                Map<String, Object> descriptor = new HashMap<>();
                boolean isNothing = ax.getSuperClass().asOWLClass().isOWLNothing();
                descriptor.put("entailmentType", isNothing ? "UNSATISFIABLE_CLASS" : "SUBCLASS_OF");
                descriptor.put("subjectIri", ax.getSubClass().asOWLClass().getIRI().toString());
                if (!isNothing) {
                    descriptor.put("targetIri", ax.getSuperClass().asOWLClass().getIRI().toString());
                }
                return descriptor;
            }
        } else if (axiom instanceof OWLClassAssertionAxiom) {
            OWLClassAssertionAxiom ax = (OWLClassAssertionAxiom) axiom;
            if (!ax.getIndividual().isAnonymous() && !ax.getClassExpression().isAnonymous()) {
                Map<String, Object> descriptor = new HashMap<>();
                descriptor.put("entailmentType", "CLASS_ASSERTION");
                descriptor.put("subjectIri", ax.getIndividual().asOWLNamedIndividual().getIRI().toString());
                descriptor.put("targetIri", ax.getClassExpression().asOWLClass().getIRI().toString());
                return descriptor;
            }
        } else if (axiom instanceof OWLObjectPropertyAssertionAxiom) {
            OWLObjectPropertyAssertionAxiom ax = (OWLObjectPropertyAssertionAxiom) axiom;
            if (!ax.getSubject().isAnonymous() && !ax.getObject().isAnonymous() && !ax.getProperty().isAnonymous()) {
                Map<String, Object> descriptor = new HashMap<>();
                descriptor.put("entailmentType", "OBJECT_PROPERTY_ASSERTION");
                descriptor.put("subjectIri", ax.getSubject().asOWLNamedIndividual().getIRI().toString());
                descriptor.put("targetIri", ax.getObject().asOWLNamedIndividual().getIRI().toString());
                descriptor.put("propertyIri", ax.getProperty().getNamedProperty().getIRI().toString());
                return descriptor;
            }
        } else if (axiom instanceof OWLDataPropertyAssertionAxiom) {
            OWLDataPropertyAssertionAxiom ax = (OWLDataPropertyAssertionAxiom) axiom;
            if (!ax.getSubject().isAnonymous() && !ax.getProperty().isAnonymous()) {
                Map<String, Object> descriptor = new HashMap<>();
                descriptor.put("entailmentType", "DATA_PROPERTY_ASSERTION");
                descriptor.put("subjectIri", ax.getSubject().asOWLNamedIndividual().getIRI().toString());
                descriptor.put("propertyIri", ax.getProperty().asOWLDataProperty().getIRI().toString());
                descriptor.put("literalValue", ax.getObject().getLiteral());
                return descriptor;
            }
        }
        return null;
    }

    public static OWLOntology stripSwrlRules(OWLOntology ontology) {
        Set<OWLAxiom> axioms = ontology.getAxioms().stream()
            .filter(ax -> ax.getAxiomType() != AxiomType.SWRL_RULE)
            .collect(Collectors.toSet());
        try {
            OWLOntologyManager mgr = org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
            return mgr.createOntology(axioms);
        } catch (OWLOntologyCreationException e) {
            return ontology;
        }
    }

    public static String renderAxiom(
            OWLAxiom axiom, OWLOntology ontology, BiFunction<OWLEntity, OWLOntology, String> labelResolver) {
        if (axiom instanceof OWLClassAssertionAxiom) {
            OWLClassAssertionAxiom ax = (OWLClassAssertionAxiom) axiom;
            if (!ax.getIndividual().isAnonymous() && !ax.getClassExpression().isAnonymous()) {
                return labelResolver.apply(ax.getIndividual().asOWLNamedIndividual(), ontology) + " Type "
                    + labelResolver.apply(ax.getClassExpression().asOWLClass(), ontology);
            }
        } else if (axiom instanceof OWLSubClassOfAxiom) {
            OWLSubClassOfAxiom ax = (OWLSubClassOfAxiom) axiom;
            if (!ax.getSubClass().isAnonymous() && !ax.getSuperClass().isAnonymous()) {
                return labelResolver.apply(ax.getSubClass().asOWLClass(), ontology) + " SubClassOf "
                    + labelResolver.apply(ax.getSuperClass().asOWLClass(), ontology);
            }
        } else if (axiom instanceof OWLDisjointClassesAxiom) {
            String names = ((OWLDisjointClassesAxiom) axiom).getClassesInSignature().stream()
                .map(c -> labelResolver.apply(c, ontology))
                .collect(Collectors.joining(", "));
            return "DisjointClasses: " + names;
        } else if (axiom instanceof OWLDisjointUnionAxiom) {
            OWLDisjointUnionAxiom ax = (OWLDisjointUnionAxiom) axiom;
            String disjuncts = ax.getClassExpressions().stream()
                .filter(ce -> !ce.isAnonymous())
                .map(ce -> labelResolver.apply(ce.asOWLClass(), ontology))
                .collect(Collectors.joining(", "));
            return labelResolver.apply(ax.getOWLClass(), ontology) + " DisjointUnionOf " + disjuncts;
        } else if (axiom instanceof OWLEquivalentClassesAxiom) {
            String names = ((OWLEquivalentClassesAxiom) axiom).getClassExpressions().stream()
                .filter(ce -> !ce.isAnonymous())
                .map(ce -> labelResolver.apply(ce.asOWLClass(), ontology))
                .collect(Collectors.joining(" ≡ "));
            return "EquivalentClasses: " + names;
        } else if (axiom instanceof OWLObjectPropertyAssertionAxiom) {
            OWLObjectPropertyAssertionAxiom ax = (OWLObjectPropertyAssertionAxiom) axiom;
            if (!ax.getSubject().isAnonymous() && !ax.getObject().isAnonymous()) {
                return labelResolver.apply(ax.getSubject().asOWLNamedIndividual(), ontology) + " "
                    + labelResolver.apply(ax.getProperty().getNamedProperty(), ontology) + " "
                    + labelResolver.apply(ax.getObject().asOWLNamedIndividual(), ontology);
            }
        } else if (axiom instanceof OWLDifferentIndividualsAxiom) {
            String names = ((OWLDifferentIndividualsAxiom) axiom).getIndividualsInSignature().stream()
                .map(i -> labelResolver.apply(i, ontology))
                .collect(Collectors.joining(", "));
            return "DifferentIndividuals: " + names;
        } else if (axiom instanceof OWLSameIndividualAxiom) {
            String names = ((OWLSameIndividualAxiom) axiom).getIndividualsInSignature().stream()
                .map(i -> labelResolver.apply(i, ontology))
                .collect(Collectors.joining(" = "));
            return "SameIndividual: " + names;
        } else if (axiom instanceof OWLFunctionalObjectPropertyAxiom) {
            return "FunctionalObjectProperty: " + labelResolver.apply(
                ((OWLFunctionalObjectPropertyAxiom) axiom).getProperty().getNamedProperty(), ontology);
        } else if (axiom instanceof OWLInverseFunctionalObjectPropertyAxiom) {
            return "InverseFunctionalObjectProperty: " + labelResolver.apply(
                ((OWLInverseFunctionalObjectPropertyAxiom) axiom).getProperty().getNamedProperty(), ontology);
        }

        String rendered = axiom.toString();
        for (OWLEntity entity : axiom.getSignature()) {
            String label = labelResolver.apply(entity, ontology);
            String iri = entity.getIRI().toString();
            if (label != null && !label.isBlank() && !label.equals(iri)) {
                rendered = rendered.replace("<" + iri + ">", label);
            }
        }
        return rendered;
    }
}
