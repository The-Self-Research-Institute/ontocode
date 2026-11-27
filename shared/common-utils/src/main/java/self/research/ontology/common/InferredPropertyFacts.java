package self.research.ontology.common;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.AxiomAnnotations;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class InferredPropertyFacts {

    static final int LOOKUP_LIMIT = 50_000;
    static final long TIME_LIMIT_MILLIS = 10_000;

    record Result(Set<OWLAxiom> facts, boolean stoppedEarly, String note) {
    }

    private InferredPropertyFacts() {
    }

    static Result collect(OWLOntology ontology, OWLReasoner reasoner, OWLDataFactory df, int limit) {
        return collect(ontology, reasoner, df, limit, LOOKUP_LIMIT, TIME_LIMIT_MILLIS);
    }

    static Result collect(OWLOntology ontology, OWLReasoner reasoner, OWLDataFactory df, int limit,
                          int lookups, long timeLimitMillis) {
        Set<OWLAxiom> facts = new LinkedHashSet<>();
        List<OWLObjectProperty> objectProperties = ontology.getObjectPropertiesInSignature().stream()
                .filter(p -> !p.isOWLTopObjectProperty() && !p.isOWLBottomObjectProperty()).sorted().toList();
        List<OWLDataProperty> dataProperties = ontology.getDataPropertiesInSignature().stream()
                .filter(p -> !p.isOWLTopDataProperty() && !p.isOWLBottomDataProperty()).sorted().toList();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeLimitMillis);
        int remaining = lookups;
        try {
            for (OWLNamedIndividual individual : ontology.getIndividualsInSignature().stream().sorted().toList()) {
                for (OWLObjectProperty property : objectProperties) {
                    if (facts.size() > limit || remaining-- <= 0 || System.nanoTime() > deadline) {
                        return new Result(facts, true, null);
                    }
                    ReasoningSnapshot.throwIfStopped();
                    for (OWLNamedIndividual value : reasoner.getObjectPropertyValues(individual, property).getFlattened()) {
                        addIfNew(ontology, facts, df.getOWLObjectPropertyAssertionAxiom(property, individual, value));
                    }
                }
                for (OWLDataProperty property : dataProperties) {
                    if (facts.size() > limit || remaining-- <= 0 || System.nanoTime() > deadline) {
                        return new Result(facts, true, null);
                    }
                    ReasoningSnapshot.throwIfStopped();
                    for (OWLLiteral value : reasoner.getDataPropertyValues(individual, property)) {
                        addIfNew(ontology, facts, df.getOWLDataPropertyAssertionAxiom(property, individual, value));
                    }
                }
            }
        } catch (UnsupportedOperationException e) {
            return facts.isEmpty()
                    ? new Result(facts, false, "This reasoner cannot work out property values, so only class facts are listed.")
                    : new Result(facts, true, "This reasoner could not work out every property value, so the property facts may be incomplete.");
        } catch (RuntimeException e) {
            ReasoningSnapshot.rethrowIfStopped(e);
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return facts.isEmpty()
                    ? new Result(facts, false, "Property facts could not be worked out: " + reason)
                    : new Result(facts, true, "Some property facts could not be worked out, so the list may be incomplete: " + reason);
        }
        return new Result(facts, false, null);
    }

    private static void addIfNew(OWLOntology ontology, Set<OWLAxiom> facts, OWLAxiom fact) {
        if (!ontology.containsAxiom(fact, Imports.INCLUDED, AxiomAnnotations.IGNORE_AXIOM_ANNOTATIONS)) {
            facts.add(fact);
        }
    }

    static List<OWLAxiom> share(Collection<OWLAxiom> classFacts, Collection<OWLAxiom> propertyFacts, int limit) {
        int propertyShare = Math.min(propertyFacts.size(), Math.max(limit / 2, limit - classFacts.size()));
        int classShare = Math.min(classFacts.size(), limit - propertyShare);
        List<OWLAxiom> shown = new ArrayList<>(classShare + propertyShare);
        classFacts.stream().limit(classShare).forEach(shown::add);
        propertyFacts.stream().limit(propertyShare).forEach(shown::add);
        return shown;
    }
}
