package self.research.ontology.owlEditor.service.owlapi;

import org.semanticweb.owlapi.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.config.FastOpenCondition;
import self.research.ontology.owlEditor.dto.DatatypeDto;

import java.util.*;

import static self.research.ontology.owlEditor.service.owlapi.OwlApiQuerySupport.*;

@Service
@Conditional(FastOpenCondition.class)
public class OwlApiDatatypeQueryService {

    @Autowired
    private OwlApiOntologyContext context;

    public List<DatatypeDto> list(String projectId, int limit, int offset) {
        return context.withOntology(projectId, (ont, reasoner) -> buildList(ont, limit, offset), List.of());
    }

    public List<Map<String, String>> usage(String projectId, String datatypeIri) {
        return context.withOntology(projectId, (ont, reasoner) -> buildUsage(ont, datatypeIri), List.of());
    }

    private List<DatatypeDto> buildList(OWLOntology ont, int limit, int offset) {
        Set<String> datatypeIris = new LinkedHashSet<>();
        ont.datatypesInSignature(IMPORTS_EXCLUDED).forEach(dt -> datatypeIris.add(dt.getIRI().toString()));
        datatypeIris.addAll(standardDatatypeIris());

        List<DatatypeDto> all = new ArrayList<>();
        for (String iri : datatypeIris) {
            DatatypeDto dto = new DatatypeDto();
            dto.setId(iri);
            dto.setIri(iri);
            dto.setLabel(IRI.create(iri).getShortForm());
            all.add(dto);
        }
        return paginate(all, limit, offset);
    }

    private List<Map<String, String>> buildUsage(OWLOntology ont, String datatypeIri) {
        IRI iri = IRI.create(datatypeIri);
        List<Map<String, String>> usages = new ArrayList<>();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLDatatype datatype = df.getOWLDatatype(iri);

        ont.dataPropertiesInSignature(IMPORTS_EXCLUDED).forEach(prop -> {
            ont.dataPropertyRangeAxioms(prop).forEach(ax -> {
                OWLDataRange range = ax.getRange();
                if (range instanceof OWLDatatype dt && dt.equals(datatype)) {
                    usages.add(usageEntry("range", prop.getIRI().toString(), getLabel(ont, prop.getIRI()),
                        "Range of data property"));
                }
            });
        });

        for (OWLSubClassOfAxiom ax : ont.getAxioms(AxiomType.SUBCLASS_OF)) {
            OWLClassExpression superClass = ax.getSuperClass();
            OWLClassExpression sub = ax.getSubClass();
            if (sub.isAnonymous() || !referencesDatatype(superClass, datatype)) continue;
            OWLClass cls = sub.asOWLClass();
            usages.add(usageEntry("restriction", cls.getIRI().toString(), getLabel(ont, cls.getIRI()),
                "Used in data restriction"));
        }

        return usages;
    }

    private boolean referencesDatatype(OWLClassExpression ce, OWLDatatype datatype) {
        if (ce instanceof OWLDataSomeValuesFrom r && r.getFiller() instanceof OWLDatatype dt) {
            return dt.equals(datatype);
        }
        if (ce instanceof OWLDataAllValuesFrom r && r.getFiller() instanceof OWLDatatype dt) {
            return dt.equals(datatype);
        }
        if (ce instanceof OWLDataHasValue r) {
            return r.getFiller().getDatatype().equals(datatype);
        }
        if (ce instanceof OWLObjectIntersectionOf inter) {
            return inter.operands().anyMatch(op -> referencesDatatype(op, datatype));
        }
        return false;
    }
}
