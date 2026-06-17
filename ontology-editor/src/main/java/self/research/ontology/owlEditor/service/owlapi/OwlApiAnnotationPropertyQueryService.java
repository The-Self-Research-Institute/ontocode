package self.research.ontology.owlEditor.service.owlapi;

import org.semanticweb.owlapi.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.config.FastOpenCondition;
import self.research.ontology.owlEditor.dto.AnnotationPropertyDto;

import java.util.*;
import java.util.stream.Collectors;

import static self.research.ontology.owlEditor.service.owlapi.OwlApiQuerySupport.*;

@Service
@Conditional(FastOpenCondition.class)
public class OwlApiAnnotationPropertyQueryService {

    @Autowired
    private OwlApiOntologyContext context;

    public List<AnnotationPropertyDto> list(String projectId, int limit, int offset) {
        return context.withOntology(projectId, (ont, reasoner) -> buildList(ont, limit, offset), List.of());
    }

    public List<Map<String, String>> usage(String projectId, String propertyIri) {
        return context.withOntology(projectId, (ont, reasoner) -> buildUsage(ont, propertyIri), List.of());
    }

    private List<AnnotationPropertyDto> buildList(OWLOntology ont, int limit, int offset) {
        List<AnnotationPropertyDto> all = new ArrayList<>();
        ont.annotationPropertiesInSignature(IMPORTS_EXCLUDED).forEach(prop -> {
            if (prop.isBuiltIn()) return;
            AnnotationPropertyDto dto = new AnnotationPropertyDto();
            String iri = prop.getIRI().toString();
            dto.setId(iri);
            dto.setIri(iri);
            dto.setLabel(getLabel(ont, prop.getIRI()));
            dto.setDescription(getComment(ont, prop.getIRI()));
            dto.setSuperProperties(superProperties(ont, prop));
            dto.setAnnotations(collectAnnotations(ont, prop.getIRI()));
            all.add(dto);
        });
        all.sort(Comparator.comparing(AnnotationPropertyDto::getLabel, String.CASE_INSENSITIVE_ORDER));
        return paginate(all, limit, offset);
    }

    private List<String> superProperties(OWLOntology ont, OWLAnnotationProperty prop) {
        List<String> supers = new ArrayList<>();
        for (OWLSubAnnotationPropertyOfAxiom ax : ont.getAxioms(AxiomType.SUB_ANNOTATION_PROPERTY_OF)) {
            if (!ax.getSubProperty().equals(prop)) continue;
            supers.add(ax.getSuperProperty().getIRI().toString());
        }
        return supers.stream().distinct().collect(Collectors.toList());
    }

    private List<Map<String, String>> buildUsage(OWLOntology ont, String propertyIri) {
        IRI iri = IRI.create(propertyIri);
        List<Map<String, String>> usages = new ArrayList<>();
        String propertyLabel = getLabel(ont, iri);

        Map<String, String> declaration = new LinkedHashMap<>();
        declaration.put("type", "declaration");
        declaration.put("subject", propertyIri);
        declaration.put("subjectLabel", propertyLabel);
        declaration.put("context", "AnnotationProperty: '" + propertyLabel + "'");
        usages.add(declaration);

        collectAnnotations(ont, iri).forEach((annProp, values) -> {
            for (String value : values) {
                Map<String, String> usage = usageEntry("annotation", propertyIri, propertyLabel,
                    "'" + propertyLabel + "' " + IRI.create(annProp).getShortForm() + " \"" + value + "\"");
                usage.put("predicate", annProp);
                usage.put("value", value);
                usages.add(usage);
            }
        });

        for (OWLAnnotationAssertionAxiom ax : ont.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
            if (!ax.getProperty().getIRI().equals(iri)) continue;
            String subjectIri = subjectIri(ax.getSubject());
            if (subjectIri == null || subjectIri.equals(propertyIri)) continue;
            String value = ax.getValue().asLiteral().map(OWLLiteral::getLiteral)
                .orElseGet(() -> ax.getValue().asIRI().map(IRI::toString).orElse(""));
            String subjectLabel = subjectLabel(ont, ax.getSubject(), subjectIri);
            Map<String, String> usage = usageEntry("annotation", subjectIri, subjectLabel,
                subjectLabel + " '" + propertyLabel + "' " + value);
            usage.put("predicate", propertyIri);
            usage.put("value", value);
            usages.add(usage);
        }

        return usages;
    }

    private String subjectIri(OWLAnnotationSubject subject) {
        if (subject instanceof OWLNamedIndividual ind) {
            return ind.getIRI().toString();
        }
        if (subject instanceof IRI iri) {
            return iri.toString();
        }
        return null;
    }

    private String subjectLabel(OWLOntology ont, OWLAnnotationSubject subject, String subjectIri) {
        if (subject instanceof OWLNamedIndividual ind) {
            return getLabel(ont, ind.getIRI());
        }
        if (subject instanceof OWLAnonymousIndividual) {
            return "anonymous";
        }
        return IRI.create(subjectIri).getShortForm();
    }
}
