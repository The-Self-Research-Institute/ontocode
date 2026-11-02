package self.research.ontology.reasoner.service;

import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLInverseObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObjectIntersectionOf;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectUnionOf;
import org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSameIndividualAxiom;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.util.ShortFormProvider;
import self.research.ontology.common.JustificationRendering;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

final class InferenceExplanationView {

    static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    static final String RDFS_SUBCLASS_OF = "http://www.w3.org/2000/01/rdf-schema#subClassOf";
    static final String RDFS_SUBPROPERTY_OF = "http://www.w3.org/2000/01/rdf-schema#subPropertyOf";
    static final String RDFS_DOMAIN = "http://www.w3.org/2000/01/rdf-schema#domain";
    static final String RDFS_RANGE = "http://www.w3.org/2000/01/rdf-schema#range";
    static final String OWL_EQUIVALENT_CLASS = "http://www.w3.org/2002/07/owl#equivalentClass";
    static final String OWL_INVERSE_OF = "http://www.w3.org/2002/07/owl#inverseOf";
    static final String OWL_SAME_AS = "http://www.w3.org/2002/07/owl#sameAs";

    private static final Pattern BLANK_NODE = Pattern.compile("<urn:ontocode:blank:[^>]*>");

    private final OWLOntology ontology;
    private final BiFunction<OWLEntity, OWLOntology, String> labels;

    InferenceExplanationView(OWLOntology ontology, BiFunction<OWLEntity, OWLOntology, String> labels) {
        this.ontology = ontology;
        this.labels = labels;
    }

    Map<String, Object> toMap(InferenceExplainer.Outcome outcome, long loadMs, long budgetMs, String requestedReasoner) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean failed = outcome.status() == InferenceExplainer.Status.FAILED;
        result.put("success", !failed);
        if (failed) {
            result.put("error", outcome.message());
        }
        result.put("explainStatus", outcome.status().name());
        result.put("message", outcome.message());
        if (outcome.conclusion() != null) {
            result.put("conclusion", axiom(outcome.conclusion(), "CONCLUSION"));
        }
        List<Map<String, Object>> justification = new ArrayList<>();
        for (OWLAxiom axiom : outcome.axioms()) {
            justification.add(axiom(axiom, axiom instanceof SWRLRule ? "RULE" : "ASSERTED"));
        }
        result.put("justification", justification);
        result.put("axiomCount", justification.size());
        result.put("minimal", outcome.minimal());
        result.put("partial", outcome.partial());
        result.put("timeBudgetHit", outcome.budgetHit());
        result.put("usesRules", outcome.usesRules());
        result.put("reasoner", outcome.reasoner());
        result.put("reasonerRequested", requestedReasoner);
        result.put("reasonerFallback", outcome.reasonerFallback());
        result.put("elapsedMs", outcome.elapsedMs());
        result.put("loadMs", loadMs);
        result.put("budgetMs", budgetMs);
        result.put("ontologyAxiomCount", outcome.ontologyAxiomCount());
        result.put("entailmentChecks", outcome.entailmentChecks());
        return result;
    }

    Map<String, Object> axiom(OWLAxiom axiom, String role) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("axiomType", axiom.getAxiomType().getName());
        entry.put("role", role);
        entry.put("text", text(axiom));
        Map<String, Object> parts = parts(axiom);
        entry.put("subjectIri", parts.get("subjectIri"));
        entry.put("predicateIri", parts.get("predicateIri"));
        entry.put("objectIri", parts.get("objectIri"));
        entry.put("objectLiteral", parts.get("objectLiteral"));
        entry.put("literalDatatypeIri", parts.get("literalDatatypeIri"));
        entry.put("objectIris", parts.get("objectIris"));
        entry.put("objectOperator", parts.get("objectOperator"));
        entry.put("entities", JustificationRendering.extractEntities(axiom, ontology, labels));
        return entry;
    }

    private String text(OWLAxiom axiom) {
        try {
            ManchesterOWLSyntaxOWLObjectRendererImpl renderer = new ManchesterOWLSyntaxOWLObjectRendererImpl();
            renderer.setShortFormProvider(new ShortFormProvider() {
                @Override
                public String getShortForm(OWLEntity entity) {
                    String label = labels.apply(entity, ontology);
                    return label == null || label.isBlank() ? entity.getIRI().getShortForm() : label;
                }

                @Override
                public void dispose() {
                }
            });
            String rendered = renderer.render(axiom).replaceAll("\\s+", " ").trim();
            return BLANK_NODE.matcher(rendered).replaceAll("_:anonymous");
        } catch (RuntimeException e) {
            return BLANK_NODE.matcher(JustificationRendering.renderAxiom(axiom, ontology, labels)).replaceAll("_:anonymous");
        }
    }

    private static Map<String, Object> parts(OWLAxiom axiom) {
        Map<String, Object> parts = new HashMap<>();
        if (axiom instanceof OWLClassAssertionAxiom a) {
            put(parts, "subjectIri", individual(a.getIndividual()));
            parts.put("predicateIri", RDF_TYPE);
            classSide(parts, a.getClassExpression());
        } else if (axiom instanceof OWLSubClassOfAxiom a) {
            put(parts, "subjectIri", named(a.getSubClass()));
            parts.put("predicateIri", RDFS_SUBCLASS_OF);
            classSide(parts, a.getSuperClass());
        } else if (axiom instanceof OWLObjectPropertyAssertionAxiom a) {
            put(parts, "subjectIri", individual(a.getSubject()));
            put(parts, "predicateIri", a.getProperty().isAnonymous() ? null : a.getProperty().asOWLObjectProperty().getIRI().toString());
            put(parts, "objectIri", individual(a.getObject()));
        } else if (axiom instanceof OWLDataPropertyAssertionAxiom a) {
            put(parts, "subjectIri", individual(a.getSubject()));
            put(parts, "predicateIri", a.getProperty().isAnonymous() ? null : a.getProperty().asOWLDataProperty().getIRI().toString());
            OWLLiteral literal = a.getObject();
            parts.put("objectLiteral", literal.getLiteral());
            parts.put("literalDatatypeIri", literal.getDatatype().getIRI().toString());
        } else if (axiom instanceof OWLObjectPropertyDomainAxiom a) {
            put(parts, "subjectIri", a.getProperty().isAnonymous() ? null : a.getProperty().asOWLObjectProperty().getIRI().toString());
            parts.put("predicateIri", RDFS_DOMAIN);
            put(parts, "objectIri", named(a.getDomain()));
        } else if (axiom instanceof OWLObjectPropertyRangeAxiom a) {
            put(parts, "subjectIri", a.getProperty().isAnonymous() ? null : a.getProperty().asOWLObjectProperty().getIRI().toString());
            parts.put("predicateIri", RDFS_RANGE);
            put(parts, "objectIri", named(a.getRange()));
        } else if (axiom instanceof OWLSubObjectPropertyOfAxiom a) {
            put(parts, "subjectIri", a.getSubProperty().isAnonymous() ? null : a.getSubProperty().asOWLObjectProperty().getIRI().toString());
            parts.put("predicateIri", RDFS_SUBPROPERTY_OF);
            put(parts, "objectIri", a.getSuperProperty().isAnonymous() ? null : a.getSuperProperty().asOWLObjectProperty().getIRI().toString());
        } else if (axiom instanceof OWLInverseObjectPropertiesAxiom a) {
            put(parts, "subjectIri", a.getFirstProperty().isAnonymous() ? null : a.getFirstProperty().asOWLObjectProperty().getIRI().toString());
            parts.put("predicateIri", OWL_INVERSE_OF);
            put(parts, "objectIri", a.getSecondProperty().isAnonymous() ? null : a.getSecondProperty().asOWLObjectProperty().getIRI().toString());
        } else if (axiom instanceof OWLEquivalentClassesAxiom a) {
            List<OWLClassExpression> named = a.getClassExpressions().stream().filter(c -> !c.isAnonymous()).sorted().toList();
            if (a.getClassExpressions().size() == 2 && !named.isEmpty()) {
                put(parts, "subjectIri", named(named.get(0)));
                parts.put("predicateIri", OWL_EQUIVALENT_CLASS);
                put(parts, "objectIri", named.size() == 2 ? named(named.get(1)) : null);
            }
        } else if (axiom instanceof OWLSameIndividualAxiom a) {
            List<OWLIndividual> named = a.getIndividuals().stream().filter(OWLIndividual::isNamed).sorted().toList();
            if (named.size() == 2) {
                put(parts, "subjectIri", individual(named.get(0)));
                parts.put("predicateIri", OWL_SAME_AS);
                put(parts, "objectIri", individual(named.get(1)));
            }
        }
        return parts;
    }

    private static void classSide(Map<String, Object> parts, OWLClassExpression expression) {
        if (!expression.isAnonymous()) {
            parts.put("objectIri", expression.asOWLClass().getIRI().toString());
            return;
        }
        String operator = expression instanceof OWLObjectUnionOf ? "UNION"
                : expression instanceof OWLObjectIntersectionOf ? "INTERSECTION" : null;
        if (operator == null) {
            return;
        }
        List<OWLClassExpression> operands = new ArrayList<>("UNION".equals(operator)
                ? expression.asDisjunctSet() : expression.asConjunctSet());
        if (operands.stream().anyMatch(OWLClassExpression::isAnonymous)) {
            return;
        }
        parts.put("objectIris", operands.stream().map(c -> c.asOWLClass().getIRI().toString()).sorted().toList());
        parts.put("objectOperator", operator);
    }

    private static void put(Map<String, Object> parts, String key, String value) {
        if (value != null) {
            parts.put(key, value);
        }
    }

    private static String named(OWLClassExpression expression) {
        return expression.isAnonymous() ? null : expression.asOWLClass().getIRI().toString();
    }

    private static String individual(OWLIndividual individual) {
        if (!individual.isNamed()) {
            return null;
        }
        String iri = individual.asOWLNamedIndividual().getIRI().toString();
        return iri.startsWith("urn:ontocode:blank:") ? null : iri;
    }
}
