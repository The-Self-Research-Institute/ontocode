package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

import java.util.LinkedHashMap;
import java.util.Map;

final class ModelMetrics {

    private static final Map<String, AxiomType<?>> AXIOM_COUNTS = new LinkedHashMap<>();

    static {
        AXIOM_COUNTS.put("declarationAxiomCount", AxiomType.DECLARATION);
        AXIOM_COUNTS.put("subClassOfAxiomCount", AxiomType.SUBCLASS_OF);
        AXIOM_COUNTS.put("equivalentClassesAxiomCount", AxiomType.EQUIVALENT_CLASSES);
        AXIOM_COUNTS.put("disjointClassesAxiomCount", AxiomType.DISJOINT_CLASSES);
        AXIOM_COUNTS.put("subObjectPropertyOfAxiomCount", AxiomType.SUB_OBJECT_PROPERTY);
        AXIOM_COUNTS.put("equivalentObjectPropertiesAxiomCount", AxiomType.EQUIVALENT_OBJECT_PROPERTIES);
        AXIOM_COUNTS.put("inverseObjectPropertiesAxiomCount", AxiomType.INVERSE_OBJECT_PROPERTIES);
        AXIOM_COUNTS.put("disjointObjectPropertiesAxiomCount", AxiomType.DISJOINT_OBJECT_PROPERTIES);
        AXIOM_COUNTS.put("objectPropertyDomainAxiomCount", AxiomType.OBJECT_PROPERTY_DOMAIN);
        AXIOM_COUNTS.put("objectPropertyRangeAxiomCount", AxiomType.OBJECT_PROPERTY_RANGE);
        AXIOM_COUNTS.put("functionalObjectPropertyAxiomCount", AxiomType.FUNCTIONAL_OBJECT_PROPERTY);
        AXIOM_COUNTS.put("inverseFunctionalObjectPropertyAxiomCount", AxiomType.INVERSE_FUNCTIONAL_OBJECT_PROPERTY);
        AXIOM_COUNTS.put("transitiveObjectPropertyAxiomCount", AxiomType.TRANSITIVE_OBJECT_PROPERTY);
        AXIOM_COUNTS.put("symmetricObjectPropertyAxiomCount", AxiomType.SYMMETRIC_OBJECT_PROPERTY);
        AXIOM_COUNTS.put("asymmetricObjectPropertyAxiomCount", AxiomType.ASYMMETRIC_OBJECT_PROPERTY);
        AXIOM_COUNTS.put("reflexiveObjectPropertyAxiomCount", AxiomType.REFLEXIVE_OBJECT_PROPERTY);
        AXIOM_COUNTS.put("irreflexiveObjectPropertyAxiomCount", AxiomType.IRREFLEXIVE_OBJECT_PROPERTY);
        AXIOM_COUNTS.put("negativeObjectPropertyAssertionAxiomCount", AxiomType.NEGATIVE_OBJECT_PROPERTY_ASSERTION);
        AXIOM_COUNTS.put("subDataPropertyOfAxiomCount", AxiomType.SUB_DATA_PROPERTY);
        AXIOM_COUNTS.put("equivalentDataPropertiesAxiomCount", AxiomType.EQUIVALENT_DATA_PROPERTIES);
        AXIOM_COUNTS.put("disjointDataPropertiesAxiomCount", AxiomType.DISJOINT_DATA_PROPERTIES);
        AXIOM_COUNTS.put("dataPropertyDomainAxiomCount", AxiomType.DATA_PROPERTY_DOMAIN);
        AXIOM_COUNTS.put("dataPropertyRangeAxiomCount", AxiomType.DATA_PROPERTY_RANGE);
        AXIOM_COUNTS.put("functionalDataPropertyAxiomCount", AxiomType.FUNCTIONAL_DATA_PROPERTY);
        AXIOM_COUNTS.put("negativeDataPropertyAssertionAxiomCount", AxiomType.NEGATIVE_DATA_PROPERTY_ASSERTION);
        AXIOM_COUNTS.put("classAssertionAxiomCount", AxiomType.CLASS_ASSERTION);
        AXIOM_COUNTS.put("objectPropertyAssertionAxiomCount", AxiomType.OBJECT_PROPERTY_ASSERTION);
        AXIOM_COUNTS.put("dataPropertyAssertionAxiomCount", AxiomType.DATA_PROPERTY_ASSERTION);
        AXIOM_COUNTS.put("annotationAssertionAxiomCount", AxiomType.ANNOTATION_ASSERTION);
        AXIOM_COUNTS.put("sameIndividualAxiomCount", AxiomType.SAME_INDIVIDUAL);
        AXIOM_COUNTS.put("differentIndividualsAxiomCount", AxiomType.DIFFERENT_INDIVIDUALS);
        AXIOM_COUNTS.put("subPropertyChainOfAxiomCount", AxiomType.SUB_PROPERTY_CHAIN_OF);
        AXIOM_COUNTS.put("annotationPropertyDomainAxiomCount", AxiomType.ANNOTATION_PROPERTY_DOMAIN);
        AXIOM_COUNTS.put("annotationPropertyRangeAxiomCount", AxiomType.ANNOTATION_PROPERTY_RANGE);
        AXIOM_COUNTS.put("subAnnotationPropertyOfAxiomCount", AxiomType.SUB_ANNOTATION_PROPERTY_OF);
    }

    private ModelMetrics() {
    }

    static int annotationProperties(OWLOntology ontology) {
        return (int) ontology.annotationPropertiesInSignature(Imports.INCLUDED).filter(p -> !p.isBuiltIn()).count();
    }

    static int datatypes(OWLOntology ontology) {
        return (int) ontology.datatypesInSignature(Imports.INCLUDED).count();
    }

    static Map<String, Object> counts(OWLOntology ontology) {
        Imports imp = Imports.INCLUDED;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("classCount", (int) ontology.classesInSignature(imp).filter(c -> !c.isBuiltIn()).count());
        out.put("objectPropertyCount", (int) ontology.objectPropertiesInSignature(imp).filter(p -> !p.isBuiltIn()).count());
        out.put("dataPropertyCount", (int) ontology.dataPropertiesInSignature(imp).filter(p -> !p.isBuiltIn()).count());
        out.put("annotationPropertyCount", annotationProperties(ontology));
        out.put("individualCount", (int) ontology.individualsInSignature(imp).filter(i -> !i.isBuiltIn()).count());
        out.put("datatypeCount", datatypes(ontology));
        out.put("axiomCount", ontology.getAxiomCount(imp));
        out.put("logicalAxiomCount", ontology.getLogicalAxiomCount(imp));
        AXIOM_COUNTS.forEach((key, type) -> out.put(key, ontology.getAxiomCount(type, imp)));
        out.put("gciCount", (int) ontology.generalClassAxioms().count());
        return out;
    }
}
