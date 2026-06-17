package self.research.ontology.owlEditor.service.owlapi;

import org.semanticweb.owlapi.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.config.FastOpenCondition;
import self.research.ontology.owlEditor.dto.PropertyDto;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static self.research.ontology.owlEditor.service.owlapi.OwlApiQuerySupport.*;

@Service
@Conditional(FastOpenCondition.class)
public class OwlApiPropertyQueryService {

    @Autowired
    private OwlApiOntologyContext context;

    public List<PropertyDto> list(String projectId, String type, int limit, int offset) {
        return context.withOntology(projectId, (ont, reasoner) -> buildList(ont, type, limit, offset), List.of());
    }

    public PropertyDto detail(String projectId, String propertyIri) {
        return context.withOntology(projectId, (ont, reasoner) -> buildDetail(ont, propertyIri), new PropertyDto());
    }

    public List<Map<String, String>> usage(String projectId, String propertyIri) {
        return context.withOntology(projectId, (ont, reasoner) -> buildUsage(ont, propertyIri), List.of());
    }

    private List<PropertyDto> buildList(OWLOntology ont, String type, int limit, int offset) {
        String norm = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        List<PropertyDto> all = new ArrayList<>();

        if (!"data".equals(norm)) {
            ont.objectPropertiesInSignature(IMPORTS_EXCLUDED).forEach(prop -> {
                if (!prop.isBuiltIn()) {
                    all.add(summaryDto(ont, prop.getIRI(), "ObjectProperty", superObjectProperties(ont, prop)));
                }
            });
        }
        if (!"object".equals(norm)) {
            ont.dataPropertiesInSignature(IMPORTS_EXCLUDED).forEach(prop -> {
                if (!prop.isBuiltIn()) {
                    all.add(summaryDto(ont, prop.getIRI(), "DatatypeProperty", superDataProperties(ont, prop)));
                }
            });
        }

        all.sort(Comparator.comparing(PropertyDto::getLabel, String.CASE_INSENSITIVE_ORDER));
        return paginate(all, limit, offset);
    }

    private PropertyDto summaryDto(OWLOntology ont, IRI iri, String type, List<String> superProperties) {
        PropertyDto dto = new PropertyDto();
        String iriStr = iri.toString();
        dto.setId(iriStr);
        dto.setIri(iriStr);
        dto.setLabel(getLabel(ont, iri));
        dto.setDescription(getComment(ont, iri));
        dto.setType(type);
        dto.setSuperProperties(superProperties);
        dto.setAnnotations(collectAnnotations(ont, iri));
        return dto;
    }

    private PropertyDto buildDetail(OWLOntology ont, String propertyIri) {
        IRI iri = IRI.create(propertyIri);
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLObjectProperty objProp = df.getOWLObjectProperty(iri);
        OWLDataProperty dataProp = df.getOWLDataProperty(iri);

        boolean isObject = ont.containsObjectPropertyInSignature(iri, IMPORTS_EXCLUDED);
        boolean isData = ont.containsDataPropertyInSignature(iri, IMPORTS_EXCLUDED);
        if (!isObject && !isData) {
            return new PropertyDto();
        }

        PropertyDto dto = summaryDto(ont, iri,
            isObject ? "ObjectProperty" : "DatatypeProperty",
            isObject ? superObjectProperties(ont, objProp) : superDataProperties(ont, dataProp));

        if (isObject) {
            dto.setDomains(domainIris(ont.objectPropertyDomainAxioms(objProp)));
            dto.setRanges(rangeIris(ont.objectPropertyRangeAxioms(objProp)));
            dto.setInverseProperties(inverseIris(ont, objProp));
            dto.setDisjointProperties(disjointObjectIris(ont, objProp));
            dto.setEquivalentProperties(equivalentObjectIris(ont, objProp));
            dto.setCharacteristics(objectCharacteristics(ont, objProp));
            dto.setSubProperties(subObjectIris(ont, objProp));
        } else {
            dto.setDomains(domainIris(ont.dataPropertyDomainAxioms(dataProp)));
            dto.setRanges(dataRangeIris(ont.dataPropertyRangeAxioms(dataProp)));
            dto.setEquivalentProperties(equivalentDataIris(ont, dataProp));
            dto.setCharacteristics(dataCharacteristics(ont, dataProp));
            dto.setSubProperties(subDataIris(ont, dataProp));
        }
        dto.setPropertyChains(propertyChains(ont, iri));
        return dto;
    }

    private List<Map<String, String>> buildUsage(OWLOntology ont, String propertyIri) {
        IRI iri = IRI.create(propertyIri);
        List<Map<String, String>> usages = new ArrayList<>();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLObjectProperty objProp = df.getOWLObjectProperty(iri);
        OWLDataProperty dataProp = df.getOWLDataProperty(iri);

        if (ont.containsObjectPropertyInSignature(iri, IMPORTS_EXCLUDED)) {
            ont.objectPropertyDomainAxioms(objProp).forEach(ax -> addDomainUsage(ont, usages, ax.getDomain()));
            ont.objectPropertyRangeAxioms(objProp).forEach(ax -> addRangeClassUsage(ont, usages, ax.getRange()));
            ont.objectSubPropertyAxiomsForSuperProperty(objProp).forEach(ax -> {
                if (!ax.getSubProperty().isAnonymous()) {
                    OWLObjectProperty sub = ax.getSubProperty().asOWLObjectProperty();
                    usages.add(usageEntry("subproperty", sub.getIRI().toString(), getLabel(ont, sub.getIRI()), "SubPropertyOf"));
                }
            });
            ont.objectSubPropertyAxiomsForSubProperty(objProp).forEach(ax -> {
                if (!ax.getSuperProperty().isAnonymous()) {
                    OWLObjectProperty sup = ax.getSuperProperty().asOWLObjectProperty();
                    usages.add(usageEntry("superproperty", sup.getIRI().toString(), getLabel(ont, sup.getIRI()), "SuperPropertyOf"));
                }
            });
            for (OWLObjectPropertyAssertionAxiom ax : ont.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION)) {
                if (!ax.getProperty().equals(objProp)) continue;
                OWLIndividual subject = ax.getSubject();
                if (subject.isNamed()) {
                    usages.add(usageEntry("assertion", subject.asOWLNamedIndividual().getIRI().toString(),
                        getLabel(ont, subject.asOWLNamedIndividual().getIRI()), "Property assertion"));
                }
            }
            addRestrictionUsages(ont, objProp.getIRI().toString(), usages, true);
        }

        if (ont.containsDataPropertyInSignature(iri, IMPORTS_EXCLUDED)) {
            ont.dataPropertyDomainAxioms(dataProp).forEach(ax -> addDomainUsage(ont, usages, ax.getDomain()));
            ont.dataPropertyRangeAxioms(dataProp).forEach(ax -> {
                OWLDataRange range = ax.getRange();
                if (range instanceof OWLDatatype dt) {
                    usages.add(usageEntry("range", dt.getIRI().toString(), dt.getIRI().getShortForm(), "Range of property"));
                }
            });
            ont.dataSubPropertyAxiomsForSuperProperty(dataProp).forEach(ax -> {
                if (!ax.getSubProperty().isAnonymous()) {
                    OWLDataProperty sub = ax.getSubProperty().asOWLDataProperty();
                    usages.add(usageEntry("subproperty", sub.getIRI().toString(), getLabel(ont, sub.getIRI()), "SubPropertyOf"));
                }
            });
            for (OWLDataPropertyAssertionAxiom ax : ont.getAxioms(AxiomType.DATA_PROPERTY_ASSERTION)) {
                if (!ax.getProperty().equals(dataProp)) continue;
                OWLIndividual subject = ax.getSubject();
                if (subject.isNamed()) {
                    usages.add(usageEntry("assertion", subject.asOWLNamedIndividual().getIRI().toString(),
                        getLabel(ont, subject.asOWLNamedIndividual().getIRI()), "Property assertion"));
                }
            }
            addRestrictionUsages(ont, dataProp.getIRI().toString(), usages, false);
        }

        return usages;
    }

    private void addDomainUsage(OWLOntology ont, List<Map<String, String>> usages, OWLClassExpression domain) {
        if (!domain.isAnonymous()) {
            OWLClass cls = domain.asOWLClass();
            usages.add(usageEntry("domain", cls.getIRI().toString(), getLabel(ont, cls.getIRI()), "Domain of property"));
        }
    }

    private void addRangeClassUsage(OWLOntology ont, List<Map<String, String>> usages, OWLClassExpression range) {
        if (!range.isAnonymous()) {
            OWLClass cls = range.asOWLClass();
            usages.add(usageEntry("range", cls.getIRI().toString(), getLabel(ont, cls.getIRI()), "Range of property"));
        }
    }

    private void addRestrictionUsages(OWLOntology ont, String propertyIri, List<Map<String, String>> usages, boolean objectProperty) {
        for (OWLSubClassOfAxiom ax : ont.getAxioms(AxiomType.SUBCLASS_OF)) {
            OWLClassExpression sub = ax.getSubClass();
            if (sub.isAnonymous() || !referencesProperty(ax.getSuperClass(), propertyIri, objectProperty)) {
                continue;
            }
            OWLClass cls = sub.asOWLClass();
            usages.add(usageEntry("restriction", cls.getIRI().toString(), getLabel(ont, cls.getIRI()), "Used in restriction"));
        }
    }

    private boolean referencesProperty(OWLClassExpression ce, String propertyIri, boolean objectProperty) {
        if (ce instanceof OWLObjectIntersectionOf inter) {
            return inter.operands().anyMatch(op -> referencesProperty(op, propertyIri, objectProperty));
        }
        OWLPropertyExpression prop = propertyFromRestriction(ce);
        return propertyExpressionMatches(prop, propertyIri, objectProperty);
    }

    private OWLPropertyExpression propertyFromRestriction(OWLClassExpression ce) {
        if (ce instanceof OWLObjectSomeValuesFrom r) return r.getProperty();
        if (ce instanceof OWLObjectAllValuesFrom r) return r.getProperty();
        if (ce instanceof OWLObjectMinCardinality r) return r.getProperty();
        if (ce instanceof OWLObjectMaxCardinality r) return r.getProperty();
        if (ce instanceof OWLObjectExactCardinality r) return r.getProperty();
        if (ce instanceof OWLObjectHasValue r) return r.getProperty();
        if (ce instanceof OWLDataSomeValuesFrom r) return r.getProperty();
        if (ce instanceof OWLDataAllValuesFrom r) return r.getProperty();
        if (ce instanceof OWLDataMinCardinality r) return r.getProperty();
        if (ce instanceof OWLDataMaxCardinality r) return r.getProperty();
        if (ce instanceof OWLDataExactCardinality r) return r.getProperty();
        if (ce instanceof OWLDataHasValue r) return r.getProperty();
        return null;
    }

    private boolean propertyExpressionMatches(OWLPropertyExpression prop, String propertyIri, boolean objectProperty) {
        if (prop == null || prop.isAnonymous()) return false;
        if (objectProperty && prop instanceof OWLObjectPropertyExpression) {
            return prop.asOWLObjectProperty().getIRI().toString().equals(propertyIri);
        }
        if (!objectProperty && prop instanceof OWLDataPropertyExpression) {
            return prop.asOWLDataProperty().getIRI().toString().equals(propertyIri);
        }
        return false;
    }

    private List<String> superObjectProperties(OWLOntology ont, OWLObjectProperty prop) {
        return ont.objectSubPropertyAxiomsForSubProperty(prop)
            .map(OWLSubObjectPropertyOfAxiom::getSuperProperty)
            .filter(sp -> !sp.isAnonymous())
            .map(sp -> sp.asOWLObjectProperty().getIRI().toString())
            .distinct().collect(Collectors.toList());
    }

    private List<String> superDataProperties(OWLOntology ont, OWLDataProperty prop) {
        return ont.dataSubPropertyAxiomsForSubProperty(prop)
            .map(OWLSubDataPropertyOfAxiom::getSuperProperty)
            .filter(sp -> !sp.isAnonymous())
            .map(sp -> sp.asOWLDataProperty().getIRI().toString())
            .distinct().collect(Collectors.toList());
    }

    private List<String> subObjectIris(OWLOntology ont, OWLObjectProperty prop) {
        return ont.objectSubPropertyAxiomsForSuperProperty(prop)
            .map(OWLSubObjectPropertyOfAxiom::getSubProperty)
            .filter(sp -> !sp.isAnonymous())
            .map(sp -> sp.asOWLObjectProperty().getIRI().toString())
            .distinct().collect(Collectors.toList());
    }

    private List<String> subDataIris(OWLOntology ont, OWLDataProperty prop) {
        return ont.dataSubPropertyAxiomsForSuperProperty(prop)
            .map(OWLSubDataPropertyOfAxiom::getSubProperty)
            .filter(sp -> !sp.isAnonymous())
            .map(sp -> sp.asOWLDataProperty().getIRI().toString())
            .distinct().collect(Collectors.toList());
    }

    private List<String> domainIris(Stream<? extends OWLPropertyDomainAxiom<?>> axioms) {
        return axioms
            .map(OWLPropertyDomainAxiom::getDomain)
            .filter(ce -> !ce.isAnonymous())
            .map(ce -> ce.asOWLClass().getIRI().toString())
            .distinct().collect(Collectors.toList());
    }

    private List<String> rangeIris(Stream<OWLObjectPropertyRangeAxiom> axioms) {
        return axioms
            .map(OWLObjectPropertyRangeAxiom::getRange)
            .filter(ce -> !ce.isAnonymous())
            .map(ce -> ce.asOWLClass().getIRI().toString())
            .distinct().collect(Collectors.toList());
    }

    private List<String> dataRangeIris(Stream<OWLDataPropertyRangeAxiom> axioms) {
        return axioms
            .map(OWLDataPropertyRangeAxiom::getRange)
            .filter(dr -> dr instanceof OWLDatatype)
            .map(dr -> ((OWLDatatype) dr).getIRI().toString())
            .distinct().collect(Collectors.toList());
    }

    private List<String> inverseIris(OWLOntology ont, OWLObjectProperty prop) {
        List<String> inverses = new ArrayList<>();
        for (OWLInverseObjectPropertiesAxiom ax : ont.getAxioms(AxiomType.INVERSE_OBJECT_PROPERTIES)) {
            OWLObjectPropertyExpression first = ax.getFirstProperty();
            OWLObjectPropertyExpression second = ax.getSecondProperty();
            if (first.equals(prop) && !second.isAnonymous()) {
                inverses.add(second.asOWLObjectProperty().getIRI().toString());
            } else if (second.equals(prop) && !first.isAnonymous()) {
                inverses.add(first.asOWLObjectProperty().getIRI().toString());
            }
        }
        return inverses.stream().distinct().collect(Collectors.toList());
    }

    private List<String> disjointObjectIris(OWLOntology ont, OWLObjectProperty prop) {
        return ont.disjointObjectPropertiesAxioms(prop)
            .flatMap(ax -> ax.properties())
            .filter(p -> !p.equals(prop) && !p.isAnonymous())
            .map(p -> p.asOWLObjectProperty().getIRI().toString())
            .distinct().collect(Collectors.toList());
    }

    private List<String> equivalentObjectIris(OWLOntology ont, OWLObjectProperty prop) {
        return ont.equivalentObjectPropertiesAxioms(prop)
            .flatMap(ax -> ax.properties())
            .filter(p -> !p.equals(prop) && !p.isAnonymous())
            .map(p -> p.asOWLObjectProperty().getIRI().toString())
            .distinct().collect(Collectors.toList());
    }

    private List<String> equivalentDataIris(OWLOntology ont, OWLDataProperty prop) {
        return ont.equivalentDataPropertiesAxioms(prop)
            .flatMap(ax -> ax.properties())
            .filter(p -> !p.equals(prop) && !p.isAnonymous())
            .map(p -> p.asOWLDataProperty().getIRI().toString())
            .distinct().collect(Collectors.toList());
    }

    private List<String> objectCharacteristics(OWLOntology ont, OWLObjectProperty prop) {
        List<String> chars = new ArrayList<>();
        if (!ont.getFunctionalObjectPropertyAxioms(prop).isEmpty()) chars.add("Functional");
        if (!ont.getInverseFunctionalObjectPropertyAxioms(prop).isEmpty()) chars.add("InverseFunctional");
        if (!ont.getTransitiveObjectPropertyAxioms(prop).isEmpty()) chars.add("Transitive");
        if (!ont.getSymmetricObjectPropertyAxioms(prop).isEmpty()) chars.add("Symmetric");
        if (!ont.getAsymmetricObjectPropertyAxioms(prop).isEmpty()) chars.add("Asymmetric");
        if (!ont.getReflexiveObjectPropertyAxioms(prop).isEmpty()) chars.add("Reflexive");
        if (!ont.getIrreflexiveObjectPropertyAxioms(prop).isEmpty()) chars.add("Irreflexive");
        return chars;
    }

    private List<String> dataCharacteristics(OWLOntology ont, OWLDataProperty prop) {
        if (ont.getFunctionalDataPropertyAxioms(prop).isEmpty()) {
            return List.of();
        }
        return List.of("Functional");
    }

    private List<String> propertyChains(OWLOntology ont, IRI propertyIri) {
        List<String> chains = new ArrayList<>();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLObjectProperty prop = df.getOWLObjectProperty(propertyIri);
        for (OWLSubPropertyChainOfAxiom ax : ont.getAxioms(AxiomType.SUB_PROPERTY_CHAIN_OF)) {
            if (!ax.getSuperProperty().equals(prop)) continue;
            List<String> parts = ax.getPropertyChain().stream()
                .filter(p -> !p.isAnonymous())
                .map(p -> p.asOWLObjectProperty().getIRI().toString())
                .collect(Collectors.toList());
            if (parts.size() >= 2) {
                chains.add(String.join(" o ", parts));
            }
        }
        return chains;
    }
}
