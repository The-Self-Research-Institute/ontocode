package self.research.ontology.common;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;

public final class EntailmentAxiomBuilder {

    private static final OWLDataFactory DATA_FACTORY =
            OWLManager.createOWLOntologyManager().getOWLDataFactory();

    private EntailmentAxiomBuilder() {}

    public static OWLAxiom buildEntailmentAxiom(String entailmentType, String subjectIri, String targetIri,
            String propertyIri, String literalValue) {
        switch (entailmentType) {
            case "SUBCLASS_OF": {
                OWLClass subject = DATA_FACTORY.getOWLClass(IRI.create(subjectIri));
                OWLClass target = DATA_FACTORY.getOWLClass(IRI.create(targetIri));
                return DATA_FACTORY.getOWLSubClassOfAxiom(subject, target);
            }
            case "INTERSECTION_SUBCLASS_OF": {
                OWLClass memberA = DATA_FACTORY.getOWLClass(IRI.create(subjectIri));
                OWLClass memberB = DATA_FACTORY.getOWLClass(IRI.create(propertyIri));
                OWLClass target = DATA_FACTORY.getOWLClass(IRI.create(targetIri));
                OWLClassExpression intersection = DATA_FACTORY.getOWLObjectIntersectionOf(memberA, memberB);
                return DATA_FACTORY.getOWLSubClassOfAxiom(intersection, target);
            }
            case "SUBCLASS_OF_OBJECT_RESTRICTION": {
                OWLClass subject = DATA_FACTORY.getOWLClass(IRI.create(subjectIri));
                OWLObjectProperty property = DATA_FACTORY.getOWLObjectProperty(IRI.create(propertyIri));
                OWLClass filler = DATA_FACTORY.getOWLClass(IRI.create(targetIri));
                OWLClassExpression restriction = "only".equals(literalValue)
                    ? DATA_FACTORY.getOWLObjectAllValuesFrom(property, filler)
                    : DATA_FACTORY.getOWLObjectSomeValuesFrom(property, filler);
                return DATA_FACTORY.getOWLSubClassOfAxiom(subject, restriction);
            }
            case "SUBCLASS_OF_OBJECT_CARDINALITY": {
                OWLClass subject = DATA_FACTORY.getOWLClass(IRI.create(subjectIri));
                OWLObjectProperty property = DATA_FACTORY.getOWLObjectProperty(IRI.create(propertyIri));
                OWLClass filler = DATA_FACTORY.getOWLClass(IRI.create(targetIri));
                return DATA_FACTORY.getOWLSubClassOfAxiom(subject, buildObjectCardinalityRestriction(property, filler, literalValue));
            }
            case "SUBCLASS_OF_DATA_RESTRICTION": {
                OWLClass subject = DATA_FACTORY.getOWLClass(IRI.create(subjectIri));
                OWLDataProperty property = DATA_FACTORY.getOWLDataProperty(IRI.create(propertyIri));
                OWLDatatype filler = DATA_FACTORY.getOWLDatatype(IRI.create(targetIri));
                OWLClassExpression restriction = "only".equals(literalValue)
                    ? DATA_FACTORY.getOWLDataAllValuesFrom(property, filler)
                    : DATA_FACTORY.getOWLDataSomeValuesFrom(property, filler);
                return DATA_FACTORY.getOWLSubClassOfAxiom(subject, restriction);
            }
            case "UNSATISFIABLE_CLASS": {
                OWLClass subject = DATA_FACTORY.getOWLClass(IRI.create(subjectIri));
                return DATA_FACTORY.getOWLSubClassOfAxiom(subject, DATA_FACTORY.getOWLNothing());
            }
            case "CLASS_ASSERTION": {
                OWLNamedIndividual individual = DATA_FACTORY.getOWLNamedIndividual(IRI.create(subjectIri));
                OWLClass target = DATA_FACTORY.getOWLClass(IRI.create(targetIri));
                return DATA_FACTORY.getOWLClassAssertionAxiom(target, individual);
            }
            case "OBJECT_PROPERTY_ASSERTION": {
                OWLNamedIndividual subject = DATA_FACTORY.getOWLNamedIndividual(IRI.create(subjectIri));
                OWLNamedIndividual object = DATA_FACTORY.getOWLNamedIndividual(IRI.create(targetIri));
                OWLObjectProperty property = DATA_FACTORY.getOWLObjectProperty(IRI.create(propertyIri));
                return DATA_FACTORY.getOWLObjectPropertyAssertionAxiom(property, subject, object);
            }
            case "DATA_PROPERTY_ASSERTION": {
                OWLNamedIndividual subject = DATA_FACTORY.getOWLNamedIndividual(IRI.create(subjectIri));
                OWLDataProperty property = DATA_FACTORY.getOWLDataProperty(IRI.create(propertyIri));
                OWLLiteral literal = DATA_FACTORY.getOWLLiteral(literalValue);
                return DATA_FACTORY.getOWLDataPropertyAssertionAxiom(property, subject, literal);
            }
            case "DISJOINT_CLASSES": {
                OWLClass subject = DATA_FACTORY.getOWLClass(IRI.create(subjectIri));
                OWLClass target = DATA_FACTORY.getOWLClass(IRI.create(targetIri));
                return DATA_FACTORY.getOWLDisjointClassesAxiom(subject, target);
            }
            case "SUB_OBJECT_PROPERTY_OF": {
                OWLObjectProperty sub = DATA_FACTORY.getOWLObjectProperty(IRI.create(subjectIri));
                OWLObjectProperty sup = DATA_FACTORY.getOWLObjectProperty(IRI.create(targetIri));
                return DATA_FACTORY.getOWLSubObjectPropertyOfAxiom(sub, sup);
            }
            case "SUB_DATA_PROPERTY_OF": {
                OWLDataProperty sub = DATA_FACTORY.getOWLDataProperty(IRI.create(subjectIri));
                OWLDataProperty sup = DATA_FACTORY.getOWLDataProperty(IRI.create(targetIri));
                return DATA_FACTORY.getOWLSubDataPropertyOfAxiom(sub, sup);
            }
            case "INVERSE_OBJECT_PROPERTIES": {
                OWLObjectProperty propA = DATA_FACTORY.getOWLObjectProperty(IRI.create(subjectIri));
                OWLObjectProperty propB = DATA_FACTORY.getOWLObjectProperty(IRI.create(targetIri));
                return DATA_FACTORY.getOWLInverseObjectPropertiesAxiom(propA, propB);
            }
            case "OBJECT_PROPERTY_DOMAIN": {
                OWLObjectProperty property = DATA_FACTORY.getOWLObjectProperty(IRI.create(subjectIri));
                OWLClass domain = DATA_FACTORY.getOWLClass(IRI.create(targetIri));
                return DATA_FACTORY.getOWLObjectPropertyDomainAxiom(property, domain);
            }
            case "OBJECT_PROPERTY_RANGE": {
                OWLObjectProperty property = DATA_FACTORY.getOWLObjectProperty(IRI.create(subjectIri));
                OWLClass range = DATA_FACTORY.getOWLClass(IRI.create(targetIri));
                return DATA_FACTORY.getOWLObjectPropertyRangeAxiom(property, range);
            }
            case "DATA_PROPERTY_DOMAIN": {
                OWLDataProperty property = DATA_FACTORY.getOWLDataProperty(IRI.create(subjectIri));
                OWLClass domain = DATA_FACTORY.getOWLClass(IRI.create(targetIri));
                return DATA_FACTORY.getOWLDataPropertyDomainAxiom(property, domain);
            }
            case "DATA_PROPERTY_RANGE": {
                OWLDataProperty property = DATA_FACTORY.getOWLDataProperty(IRI.create(subjectIri));
                OWLDatatype range = DATA_FACTORY.getOWLDatatype(IRI.create(targetIri));
                return DATA_FACTORY.getOWLDataPropertyRangeAxiom(property, range);
            }
            case "SAME_INDIVIDUAL": {
                OWLNamedIndividual subject = DATA_FACTORY.getOWLNamedIndividual(IRI.create(subjectIri));
                OWLNamedIndividual target = DATA_FACTORY.getOWLNamedIndividual(IRI.create(targetIri));
                return DATA_FACTORY.getOWLSameIndividualAxiom(subject, target);
            }
            case "DIFFERENT_INDIVIDUALS": {
                OWLNamedIndividual subject = DATA_FACTORY.getOWLNamedIndividual(IRI.create(subjectIri));
                OWLNamedIndividual target = DATA_FACTORY.getOWLNamedIndividual(IRI.create(targetIri));
                return DATA_FACTORY.getOWLDifferentIndividualsAxiom(subject, target);
            }
            case "EQUIVALENT_OBJECT_PROPERTIES": {
                OWLObjectProperty subject = DATA_FACTORY.getOWLObjectProperty(IRI.create(subjectIri));
                OWLObjectProperty target = DATA_FACTORY.getOWLObjectProperty(IRI.create(targetIri));
                return DATA_FACTORY.getOWLEquivalentObjectPropertiesAxiom(subject, target);
            }
            case "EQUIVALENT_DATA_PROPERTIES": {
                OWLDataProperty subject = DATA_FACTORY.getOWLDataProperty(IRI.create(subjectIri));
                OWLDataProperty target = DATA_FACTORY.getOWLDataProperty(IRI.create(targetIri));
                return DATA_FACTORY.getOWLEquivalentDataPropertiesAxiom(subject, target);
            }
            case "DISJOINT_OBJECT_PROPERTIES": {
                OWLObjectProperty subject = DATA_FACTORY.getOWLObjectProperty(IRI.create(subjectIri));
                OWLObjectProperty target = DATA_FACTORY.getOWLObjectProperty(IRI.create(targetIri));
                return DATA_FACTORY.getOWLDisjointObjectPropertiesAxiom(subject, target);
            }
            case "DISJOINT_DATA_PROPERTIES": {
                OWLDataProperty subject = DATA_FACTORY.getOWLDataProperty(IRI.create(subjectIri));
                OWLDataProperty target = DATA_FACTORY.getOWLDataProperty(IRI.create(targetIri));
                return DATA_FACTORY.getOWLDisjointDataPropertiesAxiom(subject, target);
            }
            default:
                throw new IllegalArgumentException("Unsupported entailment type: " + entailmentType);
        }
    }

    private static OWLClassExpression buildObjectCardinalityRestriction(
            OWLObjectProperty property, OWLClass filler, String literalValue) {
        String[] parts = literalValue != null ? literalValue.split(":", 2) : new String[0];
        String kind = parts.length > 0 ? parts[0] : "min";
        int cardinality = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
        switch (kind) {
            case "max":
                return DATA_FACTORY.getOWLObjectMaxCardinality(cardinality, property, filler);
            case "exactly":
                return DATA_FACTORY.getOWLObjectExactCardinality(cardinality, property, filler);
            case "min":
            default:
                return DATA_FACTORY.getOWLObjectMinCardinality(cardinality, property, filler);
        }
    }
}
