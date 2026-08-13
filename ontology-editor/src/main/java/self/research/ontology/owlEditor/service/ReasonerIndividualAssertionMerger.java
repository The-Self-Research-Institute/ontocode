package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataPropertyExpression;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class ReasonerIndividualAssertionMerger {

    private static final Logger log = LoggerFactory.getLogger(ReasonerIndividualAssertionMerger.class);

    private final EditorReasonerCacheService editorReasonerCache;
    private final ReasonerService reasonerService;

    @Value("${ontocode.reasoner.max-inferred-assertions-per-read:200}")
    private int maxInferredPerRead;

    @Value("${ontocode.reasoner.max-property-scan-per-individual-read:400}")
    private int maxPropertyScanPerRead;

    public ReasonerIndividualAssertionMerger(EditorReasonerCacheService editorReasonerCache,
                                             ReasonerService reasonerService) {
        this.editorReasonerCache = editorReasonerCache;
        this.reasonerService = reasonerService;
    }

    public List<Map<String, Object>> mergeInferred(String projectId,
                                                   String individualIri,
                                                   List<Map<String, Object>> asserted) {
        Optional<OWLOntology> ontologyOpt = editorReasonerCache.getOntology(projectId);
        if (ontologyOpt.isEmpty()) {
            return asserted;
        }
        Optional<OWLReasoner> reasonerOpt = reasonerService.findCachedReasoner(ontologyOpt.get());
        if (reasonerOpt.isEmpty()) {
            return asserted;
        }
        return mergeInferred(ontologyOpt.get(), reasonerOpt.get(), individualIri, asserted);
    }

    public List<Map<String, Object>> mergeInferred(OWLOntology ontology,
                                                   OWLReasoner reasoner,
                                                   String individualIri,
                                                   List<Map<String, Object>> asserted) {
        if (reasoner == null || individualIri == null || individualIri.isBlank()) {
            return asserted;
        }

        OWLNamedIndividual ind = ontology.getOWLOntologyManager().getOWLDataFactory()
                .getOWLNamedIndividual(IRI.create(individualIri));
        if (!ontology.containsIndividualInSignature(ind.getIRI())) {
            return asserted;
        }

        Set<String> seenKeys = new LinkedHashSet<>();
        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> row : asserted) {
            seenKeys.add(assertionKey(row));
            merged.add(row);
        }

        int inferredAdded = 0;
        try {
            for (OWLObjectProperty prop : objectPropertiesToCheck(ontology, ind)) {
                NodeSet<OWLNamedIndividual> values = reasoner.getObjectPropertyValues(ind, prop);
                for (OWLNamedIndividual target : values.getFlattened()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("propertyIri", prop.getIRI().toString());
                    entry.put("propertyLabel", labelFor(ontology, prop.getIRI()));
                    entry.put("targetIri", target.getIRI().toString());
                    entry.put("targetLabel", labelFor(ontology, target.getIRI()));
                    entry.put("isObjectProperty", true);
                    entry.put("isInferred", true);

                    String key = assertionKey(entry);
                    if (seenKeys.contains(key)) {
                        continue;
                    }
                    if (inferredAdded >= maxInferredPerRead) {
                        log.debug("[ReasonerMerge] capped inferred property assertions for {}", individualIri);
                        return merged;
                    }
                    entry.put("id", "inferred-obj-" + inferredAdded);
                    merged.add(entry);
                    seenKeys.add(key);
                    inferredAdded++;
                }
            }

            for (OWLDataProperty prop : dataPropertiesToCheck(ontology, ind)) {
                Set<OWLLiteral> values = reasoner.getDataPropertyValues(ind, prop);
                for (OWLLiteral literal : values) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("propertyIri", prop.getIRI().toString());
                    entry.put("propertyLabel", labelFor(ontology, prop.getIRI()));
                    entry.put("targetLiteral", literal.getLiteral());
                    if (!literal.isRDFPlainLiteral() && literal.getDatatype() != null) {
                        entry.put("datatype", literal.getDatatype().getIRI().toString());
                    }
                    if (literal.hasLang()) {
                        entry.put("lang", literal.getLang());
                    }
                    entry.put("isObjectProperty", false);
                    entry.put("isInferred", true);

                    String key = assertionKey(entry);
                    if (seenKeys.contains(key)) {
                        continue;
                    }
                    if (inferredAdded >= maxInferredPerRead) {
                        log.debug("[ReasonerMerge] capped inferred property assertions for {}", individualIri);
                        return merged;
                    }
                    entry.put("id", "inferred-data-" + inferredAdded);
                    merged.add(entry);
                    seenKeys.add(key);
                    inferredAdded++;
                }
            }
        } catch (Exception e) {
            log.debug("[ReasonerMerge] inferred property assertions failed for {}: {}", individualIri, e.getMessage());
            return asserted;
        }

        if (inferredAdded > 0) {
            log.debug("[ReasonerMerge] added {} inferred property assertions for {}", inferredAdded, individualIri);
        }
        return merged;
    }

    private List<OWLObjectProperty> objectPropertiesToCheck(OWLOntology ontology, OWLNamedIndividual ind) {
        LinkedHashSet<OWLObjectProperty> props = new LinkedHashSet<>();
        ontology.objectPropertyAssertionAxioms(ind).forEach(ax -> {
            if (ax.getProperty().isNamed()) {
                props.add(ax.getProperty().asOWLObjectProperty());
            }
        });
        int scanned = 0;
        for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature(Imports.EXCLUDED)) {
            if (prop.isBuiltIn() || props.contains(prop)) {
                continue;
            }
            if (scanned >= maxPropertyScanPerRead) {
                break;
            }
            props.add(prop);
            scanned++;
        }
        return new ArrayList<>(props);
    }

    private List<OWLDataProperty> dataPropertiesToCheck(OWLOntology ontology, OWLNamedIndividual ind) {
        LinkedHashSet<OWLDataProperty> props = new LinkedHashSet<>();
        ontology.dataPropertyAssertionAxioms(ind).forEach(ax -> {
            if (ax.getProperty().isNamed()) {
                props.add(ax.getProperty().asOWLDataProperty());
            }
        });
        int scanned = 0;
        for (OWLDataProperty prop : ontology.getDataPropertiesInSignature(Imports.EXCLUDED)) {
            if (prop.isBuiltIn() || props.contains(prop)) {
                continue;
            }
            if (scanned >= maxPropertyScanPerRead) {
                break;
            }
            props.add(prop);
            scanned++;
        }
        return new ArrayList<>(props);
    }

    private static String assertionKey(Map<String, Object> assertion) {
        String prop = String.valueOf(assertion.getOrDefault("propertyIri", ""));
        boolean isObject = Boolean.TRUE.equals(assertion.get("isObjectProperty"));
        if (isObject) {
            return "obj|" + prop + "|" + assertion.getOrDefault("targetIri", "");
        }
        return "data|" + prop + "|" + assertion.getOrDefault("targetLiteral", "")
                + "|" + assertion.getOrDefault("datatype", "")
                + "|" + assertion.getOrDefault("lang", "");
    }

    private static String labelFor(OWLOntology ontology, IRI iri) {
        return ontology.getAnnotationAssertionAxioms(iri).stream()
                .filter(ax -> ax.getProperty().isLabel())
                .map(ax -> ax.getValue().asLiteral().map(OWLLiteral::getLiteral).orElse(null))
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse(iri.getShortForm());
    }
}
