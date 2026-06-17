package self.research.ontology.owlEditor.service.owlapi;

import org.semanticweb.owlapi.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.config.FastOpenCondition;
import self.research.ontology.owlEditor.dto.IndividualDto;

import java.util.*;
import java.util.stream.Collectors;

import static self.research.ontology.owlEditor.service.owlapi.OwlApiQuerySupport.*;

@Service
@Conditional(FastOpenCondition.class)
public class OwlApiIndividualQueryService {

    @Autowired
    private OwlApiOntologyContext context;

    public List<IndividualDto> list(String projectId, int limit, int offset) {
        return context.withOntology(projectId, (ont, reasoner) -> buildList(ont, limit, offset), List.of());
    }

    public long count(String projectId) {
        return context.withOntology(projectId,
            (ont, reasoner) -> ont.individualsInSignature(IMPORTS_EXCLUDED).filter(OWLIndividual::isNamed).count(),
            0L);
    }

    public List<Map<String, String>> usage(String projectId, String individualIri) {
        return context.withOntology(projectId, (ont, reasoner) -> buildUsage(ont, individualIri), List.of());
    }

    public Map<String, Object> details(String projectId, String individualIri) {
        return context.withOntology(projectId, (ont, reasoner) -> buildDetails(ont, individualIri), Map.of());
    }

    private List<IndividualDto> buildList(OWLOntology ont, int limit, int offset) {
        List<IndividualDto> all = new ArrayList<>();
        ont.individualsInSignature(IMPORTS_EXCLUDED).forEach(ind -> all.add(toDto(ont, ind)));
        all.sort(Comparator.comparing(IndividualDto::getLabel, String.CASE_INSENSITIVE_ORDER));
        return paginate(all, limit, offset);
    }

    private IndividualDto toDto(OWLOntology ont, OWLNamedIndividual ind) {
        IndividualDto dto = new IndividualDto();
        String iri = ind.getIRI().toString();
        dto.setId(iri);
        dto.setIri(iri);
        dto.setLabel(getLabel(ont, ind.getIRI()));
        dto.setDescription(getComment(ont, ind.getIRI()));
        dto.setTypes(classTypes(ont, ind));
        return dto;
    }

    private List<String> classTypes(OWLOntology ont, OWLNamedIndividual ind) {
        return ont.classAssertionAxioms(ind)
            .map(OWLClassAssertionAxiom::getClassExpression)
            .filter(ce -> !ce.isAnonymous())
            .map(ce -> ce.asOWLClass().getIRI().toString())
            .distinct()
            .collect(Collectors.toList());
    }

    private Map<String, Object> buildDetails(OWLOntology ont, String individualIri) {
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(individualIri));
        if (!ont.containsIndividualInSignature(ind.getIRI(), IMPORTS_EXCLUDED)) {
            return Map.of();
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id", individualIri);
        details.put("label", getLabel(ont, ind.getIRI()));
        details.put("types", classTypes(ont, ind));
        details.put("annotations", collectAnnotations(ont, ind.getIRI()));
        details.put("propertyAssertions", propertyAssertions(ont, ind));
        return details;
    }

    private List<Map<String, Object>> propertyAssertions(OWLOntology ont, OWLNamedIndividual ind) {
        List<Map<String, Object>> assertions = new ArrayList<>();
        int idx = 0;
        ont.objectPropertyAssertionAxioms(ind).forEach(ax -> {
            if (!ax.getObject().isNamed()) return;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", "assertion-" + assertions.size());
            OWLObjectPropertyExpression property = ax.getProperty();
            String propIri = property.asOWLObjectProperty().getIRI().toString();
            entry.put("propertyIri", propIri);
            entry.put("propertyLabel", getLabel(ont, property.asOWLObjectProperty().getIRI()));
            String target = ax.getObject().asOWLNamedIndividual().getIRI().toString();
            entry.put("targetIri", target);
            entry.put("targetLabel", getLabel(ont, ax.getObject().asOWLNamedIndividual().getIRI()));
            entry.put("isObjectProperty", true);
            assertions.add(entry);
        });
        ont.dataPropertyAssertionAxioms(ind).forEach(ax -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", "assertion-" + assertions.size());
            OWLDataPropertyExpression property = ax.getProperty();
            String propIri = property.asOWLDataProperty().getIRI().toString();
            entry.put("propertyIri", propIri);
            entry.put("propertyLabel", getLabel(ont, property.asOWLDataProperty().getIRI()));
            entry.put("targetLiteral", ax.getObject().getLiteral());
            entry.put("isObjectProperty", false);
            assertions.add(entry);
        });
        return assertions;
    }

    private List<Map<String, String>> buildUsage(OWLOntology ont, String individualIri) {
        IRI iri = IRI.create(individualIri);
        List<Map<String, String>> usages = new ArrayList<>();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLNamedIndividual ind = df.getOWLNamedIndividual(iri);
        String label = getLabel(ont, iri);

        for (OWLObjectPropertyAssertionAxiom ax : ont.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION)) {
            if (!ax.getObject().isNamed() || !ax.getObject().equals(ind)) continue;
            OWLIndividual subject = ax.getSubject();
            if (!subject.isNamed()) continue;
            OWLObjectPropertyExpression property = ax.getProperty();
            Map<String, String> usage = usageEntry("assertion",
                subject.asOWLNamedIndividual().getIRI().toString(),
                getLabel(ont, subject.asOWLNamedIndividual().getIRI()),
                "Object of " + getLabel(ont, property.asOWLObjectProperty().getIRI()));
            usage.put("predicate", property.asOWLObjectProperty().getIRI().toString());
            usages.add(usage);
        }

        for (OWLSameIndividualAxiom ax : ont.getAxioms(AxiomType.SAME_INDIVIDUAL)) {
            ax.individuals().forEach(other -> {
                if (!other.isNamed() || other.equals(ind)) return;
                usages.add(usageEntry("same", other.asOWLNamedIndividual().getIRI().toString(),
                    getLabel(ont, other.asOWLNamedIndividual().getIRI()), "SameIndividualAs"));
            });
        }

        for (OWLDifferentIndividualsAxiom ax : ont.getAxioms(AxiomType.DIFFERENT_INDIVIDUALS)) {
            boolean contains = ax.individuals().anyMatch(other -> other.equals(ind));
            if (!contains) continue;
            ax.individuals().forEach(other -> {
                if (!other.isNamed() || other.equals(ind)) return;
                usages.add(usageEntry("different", other.asOWLNamedIndividual().getIRI().toString(),
                    getLabel(ont, other.asOWLNamedIndividual().getIRI()), "DifferentIndividualFrom"));
            });
        }

        ont.annotationAssertionAxioms(iri, IMPORTS_EXCLUDED).forEach(ax -> {
            String value = ax.getValue().asLiteral().map(OWLLiteral::getLiteral)
                .orElseGet(() -> ax.getValue().asIRI().map(IRI::toString).orElse(""));
            Map<String, String> usage = usageEntry("annotation", individualIri, label,
                "'" + label + "' " + ax.getProperty().getIRI().getShortForm() + " \"" + value + "\"");
            usage.put("predicate", ax.getProperty().getIRI().toString());
            usage.put("value", value);
            usages.add(usage);
        });

        return usages;
    }
}
