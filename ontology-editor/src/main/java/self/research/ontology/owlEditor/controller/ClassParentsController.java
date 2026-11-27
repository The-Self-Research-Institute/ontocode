package self.research.ontology.owlEditor.controller;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.SparqlQueryContext;
import self.research.ontology.owlEditor.service.owlapi.OwlApiOntologyContext;
import self.research.ontology.owlEditor.service.owlapi.OwlApiQuerySupport;
import self.research.ontology.owlEditor.util.SparqlSafety;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@RestController
@CrossOrigin
public class ClassParentsController {
    private final SparqlDatasetService datasets;
    private final Optional<OwlApiOntologyContext> owlApi;

    public ClassParentsController(SparqlDatasetService datasets, Optional<OwlApiOntologyContext> owlApi) {
        this.datasets = datasets;
        this.owlApi = owlApi;
    }

    @GetMapping(value = "/api/ontology/classes/details/{projectId}", params = "view=parents")
    public Map<String, Object> directParents(@PathVariable("projectId") String projectId, @RequestParam("classIri") String classIri) {
        SparqlSafety.safeIri(classIri);
        String userId = SparqlQueryContext.getUserId();
        boolean draft = userId != null && datasets.hasActiveDraftOverlay(projectId, userId);
        List<Parent> parents = (draft ? Optional.<OWLOntology>empty() : owlApi.flatMap(context -> context.ontology(projectId)))
                .map(ontology -> fromOntology(ontology, classIri))
                .orElseGet(() -> fromGraph(projectId, classIri));
        return Map.of("success", true, "data", parents);
    }

    private static List<Parent> fromOntology(OWLOntology ontology, String classIri) {
        var subject = ontology.getOWLOntologyManager().getOWLDataFactory()
                .getOWLClass(org.semanticweb.owlapi.model.IRI.create(classIri));
        return ontology.subClassAxiomsForSubClass(subject)
                .map(axiom -> axiom.getSuperClass())
                .filter(expression -> !expression.isAnonymous())
                .map(expression -> expression.asOWLClass().getIRI())
                .distinct()
                .filter(iri -> displayableParent(iri.toString(), classIri))
                .map(iri -> new Parent(iri.toString(), OwlApiQuerySupport.getLabel(ontology, iri)))
                .sorted(Comparator.comparing(Parent::label).thenComparing(Parent::iri)).toList();
    }

    private List<Parent> fromGraph(String projectId, String classIri) {
        String query = """
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                SELECT DISTINCT ?parent ?label WHERE {
                  <%s> rdfs:subClassOf ?parent .
                  FILTER(isIRI(?parent))
                  OPTIONAL { ?parent rdfs:label ?label . FILTER(isLiteral(?label)) }
                }
                """.formatted(classIri);
        Map<String, String> labels = new TreeMap<>();
        try (var rows = datasets.execSelect(projectId, query)) {
            while (rows.hasNext()) {
                var row = rows.next();
                if (row.getValue("parent") instanceof IRI parent && displayableParent(parent.stringValue(), classIri)) {
                    String label = row.getValue("label") instanceof Literal value ? value.getLabel() : "";
                    labels.merge(parent.stringValue(), label, (current, next) ->
                            current.isBlank() || (!next.isBlank() && next.compareTo(current) < 0) ? next : current);
                }
            }
        }
        return labels.entrySet().stream().map(entry -> new Parent(entry.getKey(), entry.getValue().isBlank()
                        ? org.semanticweb.owlapi.model.IRI.create(entry.getKey()).getShortForm() : entry.getValue()))
                .sorted(Comparator.comparing(Parent::label).thenComparing(Parent::iri)).toList();
    }

    private static boolean displayableParent(String target, String subject) {
        return !target.equals(subject) && !target.startsWith("http://www.w3.org/2002/07/owl#")
                && !target.startsWith("http://www.w3.org/2000/01/rdf-schema#")
                && !target.startsWith("http://ontocode.org/restriction/");
    }

    record Parent(String iri, String label) {
    }
}
