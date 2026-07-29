package self.research.ontology.owlEditor.service.owlapi;

import org.semanticweb.owlapi.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.config.FastOpenCondition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static self.research.ontology.owlEditor.service.owlapi.OwlApiQuerySupport.IMPORTS_EXCLUDED;

/**
 * Desktop owlapi-first reads for ontology-level metadata that has a clean, identity-safe OWLAPI
 * equivalent (no downstream string-matched update/delete coupling): ontology-level annotations,
 * direct owl:imports, and the flat class/property signature used by the SWRL entity picker.
 *
 * Deliberately NOT covered here (left on the sync-then-SPARQL-read fallback, still correct just
 * not owlapi-first):
 *  - General class axioms: the SPARQL read keys GCIs by their RDF blank-node id, and GCI
 *    update/delete match against that same id string — switching the read to an OWLAPI class
 *    expression (Manchester-rendered) would desync that identity scheme and silently break edits.
 *  - Import closure: walks owl:imports transitively across the whole merged graph (including
 *    triples pulled in by manual import resolution), which isn't what a single OWLOntology's own
 *    import declarations model.
 *  - Ontology metadata / prefixes: already served from a MongoDB cache kept in sync at mutation
 *    time; Fuseki is only touched on a cache miss, so there's no staleness to fix by going OWLAPI.
 */
@Service
@Conditional(FastOpenCondition.class)
public class OwlApiOntologyMetadataQueryService {

    @Autowired
    private OwlApiOntologyContext context;

    public List<Map<String, String>> annotations(String projectId) {
        return context.withOntology(projectId, (ont, reasoner) -> buildAnnotations(ont), List.of());
    }

    public List<String> imports(String projectId) {
        return context.withOntology(projectId, (ont, reasoner) -> buildImports(ont), List.of());
    }

    public Map<String, Object> schema(String projectId) {
        return context.withOntology(projectId, (ont, reasoner) -> buildSchema(ont), Map.of());
    }

    private List<Map<String, String>> buildAnnotations(OWLOntology ont) {
        List<Map<String, String>> annotations = new ArrayList<>();
        for (OWLAnnotation annotation : ont.annotations().collect(Collectors.toList())) {
            Map<String, String> ann = new LinkedHashMap<>();
            String propertyIri = annotation.getProperty().getIRI().toString();
            ann.put("property", propertyIri);
            ann.put("propertyIri", propertyIri);
            OWLAnnotationValue value = annotation.getValue();
            if (value instanceof OWLLiteral literal) {
                ann.put("value", literal.getLiteral());
                if (literal.hasLang()) {
                    ann.put("language", literal.getLang());
                }
                String datatype = literal.getDatatype().getIRI().toString();
                if (!datatype.equals("http://www.w3.org/2001/XMLSchema#string")) {
                    ann.put("datatype", datatype);
                }
            } else {
                ann.put("value", value.toString());
            }
            annotations.add(ann);
        }
        return annotations;
    }

    private List<String> buildImports(OWLOntology ont) {
        return ont.importsDeclarations()
            .map(decl -> decl.getIRI().toString())
            .collect(Collectors.toList());
    }

    private Map<String, Object> buildSchema(OWLOntology ont) {
        Map<String, Object> schema = new LinkedHashMap<>();

        List<String> classes = ont.classesInSignature(IMPORTS_EXCLUDED)
            .filter(c -> !c.isOWLThing() && !c.isOWLNothing())
            .map(c -> c.getIRI().toString())
            .sorted()
            .limit(1000)
            .collect(Collectors.toList());
        schema.put("classes", classes);

        List<String> objectProperties = ont.objectPropertiesInSignature(IMPORTS_EXCLUDED)
            .filter(p -> !p.isBuiltIn())
            .map(p -> p.getIRI().toString())
            .sorted()
            .limit(1000)
            .collect(Collectors.toList());
        schema.put("objectProperties", objectProperties);

        List<String> dataProperties = ont.dataPropertiesInSignature(IMPORTS_EXCLUDED)
            .filter(p -> !p.isBuiltIn())
            .map(p -> p.getIRI().toString())
            .sorted()
            .limit(1000)
            .collect(Collectors.toList());
        schema.put("dataProperties", dataProperties);

        return schema;
    }
}
