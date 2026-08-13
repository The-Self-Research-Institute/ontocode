package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class ReasonerClassInstanceMerger {

    private static final Logger log = LoggerFactory.getLogger(ReasonerClassInstanceMerger.class);

    private final EditorReasonerCacheService editorReasonerCache;
    private final ReasonerService reasonerService;

    public ReasonerClassInstanceMerger(EditorReasonerCacheService editorReasonerCache,
                                       ReasonerService reasonerService) {
        this.editorReasonerCache = editorReasonerCache;
        this.reasonerService = reasonerService;
    }

    public List<Map<String, Object>> mergeInferred(String projectId,
                                                   String classIri,
                                                   List<Map<String, Object>> asserted) {
        Optional<OWLOntology> ontologyOpt = editorReasonerCache.getOntology(projectId);
        if (ontologyOpt.isEmpty()) {
            return asserted;
        }
        Optional<OWLReasoner> reasonerOpt = reasonerService.findCachedReasoner(ontologyOpt.get());
        if (reasonerOpt.isEmpty()) {
            return asserted;
        }

        OWLOntology ontology = ontologyOpt.get();
        OWLReasoner reasoner = reasonerOpt.get();
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        OWLClass cls = df.getOWLClass(IRI.create(classIri));

        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> row : asserted) {
            Object id = row.get("id");
            if (id instanceof String iri && seen.add(iri)) {
                merged.add(row);
            }
        }

        try {
            for (OWLNamedIndividual ind : reasoner.getInstances(cls, false).getFlattened()) {
                String iri = ind.getIRI().toString();
                if (!seen.add(iri)) {
                    continue;
                }
                Map<String, Object> individual = new LinkedHashMap<>();
                individual.put("id", iri);
                individual.put("label", labelFor(ontology, ind));
                individual.put("isInferred", true);
                individual.put("types", List.of(classIri));
                merged.add(individual);
            }
        } catch (Exception e) {
            log.debug("[ReasonerMerge] inferred instances failed for {}: {}", classIri, e.getMessage());
            return asserted;
        }

        merged.sort(Comparator.comparing(
                m -> String.valueOf(m.getOrDefault("label", "")),
                String.CASE_INSENSITIVE_ORDER));
        return merged;
    }

    public Map<String, Map<String, Integer>> mergeInferredCounts(String projectId,
                                                                 Map<String, Map<String, Integer>> assertedCounts) {
        Optional<OWLOntology> ontologyOpt = editorReasonerCache.getOntology(projectId);
        if (ontologyOpt.isEmpty()) {
            return assertedCounts;
        }
        Optional<OWLReasoner> reasonerOpt = reasonerService.findCachedReasoner(ontologyOpt.get());
        if (reasonerOpt.isEmpty()) {
            return assertedCounts;
        }

        OWLOntology ontology = ontologyOpt.get();
        OWLReasoner reasoner = reasonerOpt.get();
        Map<String, Map<String, Integer>> merged = new LinkedHashMap<>(assertedCounts);

        for (String classIri : new ArrayList<>(merged.keySet())) {
            try {
                OWLClass cls = ontology.getOWLOntologyManager().getOWLDataFactory()
                        .getOWLClass(IRI.create(classIri));
                Set<String> assertedIndividuals = new LinkedHashSet<>();
                for (OWLClassAssertionAxiom ax : ontology.getClassAssertionAxioms(cls)) {
                    OWLIndividual ind = ax.getIndividual();
                    if (ind.isNamed()) {
                        assertedIndividuals.add(ind.asOWLNamedIndividual().getIRI().toString());
                    }
                }

                int inferredOnly = 0;
                for (OWLNamedIndividual ind : reasoner.getInstances(cls, false).getFlattened()) {
                    if (!assertedIndividuals.contains(ind.getIRI().toString())) {
                        inferredOnly++;
                    }
                }
                if (inferredOnly == 0 && !merged.containsKey(classIri)) {
                    continue;
                }

                Map<String, Integer> entry = merged.computeIfAbsent(classIri, k -> {
                    Map<String, Integer> m = new LinkedHashMap<>();
                    m.put("direct", 0);
                    m.put("inferred", 0);
                    m.put("total", 0);
                    return m;
                });
                int direct = entry.getOrDefault("direct", 0);
                entry.put("inferred", inferredOnly);
                entry.put("total", direct + inferredOnly);
            } catch (Exception e) {
                log.trace("[ReasonerMerge] count skip {}: {}", classIri, e.getMessage());
            }
        }
        return merged;
    }

    private static String labelFor(OWLOntology ontology, OWLNamedIndividual ind) {
        return ontology.getAnnotationAssertionAxioms(ind.getIRI()).stream()
                .filter(ax -> ax.getProperty().isLabel())
                .map(ax -> ax.getValue().asLiteral().map(lit -> lit.getLiteral()).orElse(null))
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse(ind.getIRI().getShortForm());
    }
}
