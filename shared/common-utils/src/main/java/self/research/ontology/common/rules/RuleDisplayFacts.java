package self.research.ontology.common.rules;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.model.parameters.AxiomAnnotations;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import self.research.ontology.common.rules.RuleExecutionResult.DerivedFact;
import self.research.ontology.common.rules.RuleExecutionResult.Limit;
import self.research.ontology.common.rules.RuleExecutionResult.Origin;
import self.research.ontology.common.rules.RuleExecutionResult.Status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class RuleDisplayFacts {
    private final Function<OWLOntology, OWLReasoner> factory;
    private final RuleExecutionLimits limits;
    private final long started;

    RuleDisplayFacts(Function<OWLOntology, OWLReasoner> factory, RuleExecutionLimits limits, long started) {
        this.factory = factory;
        this.limits = limits;
        this.started = started;
    }

    RuleExecutionResult complete(OWLOntology asserted, RuleExecutionResult run) {
        if (run.status() == Status.FAILED || run.status() == Status.CANCELLED || run.status() == Status.INCONSISTENT) {
            return run;
        }
        Map<OWLAxiom, DerivedFact> facts = new LinkedHashMap<>();
        run.derived().forEach(fact -> facts.put(fact.axiom(), fact));
        List<String> notes = new ArrayList<>(run.limitations());
        Limit stopped = Limit.NONE;
        boolean cancelled = false;
        try {
            checkTime();
            collect(asserted, run.working(), facts, run.stopping().rounds());
        } catch (DisplayStopped e) {
            stopped = e.limit;
            cancelled = e.cancelled;
            notes.add("The run returned only some of the inferred facts because the results scan reached its limit or was stopped.");
        } catch (RuntimeException | StackOverflowError e) {
            notes.add("The reasoner could not finish collecting the inferred facts for display.");
        }
        List<DerivedFact> classified;
        try {
            classified = facts.isEmpty() || (run.executedRules().isEmpty() && run.engineRules() == 0)
                    ? withOrigin(facts, Origin.OWL_REASONER) : classify(asserted, facts);
        } catch (DisplayStopped e) {
            classified = withOrigin(facts, Origin.UNDETERMINED);
            if (stopped == Limit.NONE) {
                stopped = e.limit;
            }
            cancelled |= e.cancelled;
            notes.add("The run reached its limit or was stopped before it could separate rule-dependent facts from standard reasoning.");
        } catch (OWLOntologyCreationException | RuntimeException | StackOverflowError e) {
            classified = withOrigin(facts, Origin.UNDETERMINED);
            notes.add("The run could not finish separating rule-dependent facts from standard reasoning.");
        }

        var original = run.stopping();
        Status status = cancelled || limits.cancelled().getAsBoolean() ? Status.CANCELLED : run.status();
        boolean partial = notes.size() > run.limitations().size();
        if (status == Status.COMPLETED && partial) {
            status = Status.PARTIAL_LIMIT_REACHED;
        }
        var stopping = new RuleExecutionResult.Stopping(original.rounds(), original.maxRounds(),
                elapsed(), original.maxMillis(), original.limit() == Limit.NONE ? stopped : original.limit(), original.converged());
        return new RuleExecutionResult(status, run.working(), classified, run.executedRules(), run.executedRuleIds(),
                run.engineRules(), run.translatedRules(), run.skippedRules(), List.copyOf(notes), run.snapshotId(),
                run.engineVersions(), stopping, status == run.status() ? run.message()
                        : status == Status.CANCELLED ? "The run was stopped." : "The results are partial. See the run limitations.");
    }

    private void collect(OWLOntology asserted, OWLOntology working, Map<OWLAxiom, DerivedFact> facts, int round) {
        OWLReasoner reasoner = factory.apply(working);
        var df = working.getOWLOntologyManager().getOWLDataFactory();
        try {
            checkTime();
            for (var type : working.classesInSignature().filter(c -> !c.isBuiltIn()).sorted().toList()) {
                checkTime();
                reasoner.getInstances(type, false).entities().forEach(value ->
                        add(asserted, facts, df.getOWLClassAssertionAxiom(type, value), round));
            }
            var objects = working.objectPropertiesInSignature().filter(p -> !p.isBuiltIn()).sorted().toList();
            var data = working.dataPropertiesInSignature().filter(p -> !p.isBuiltIn()).sorted().toList();
            for (var individual : working.individualsInSignature().sorted().toList()) {
                for (var property : objects) {
                    checkTime();
                    reasoner.getObjectPropertyValues(individual, property).entities().forEach(value ->
                            add(asserted, facts, df.getOWLObjectPropertyAssertionAxiom(property, individual, value), round));
                }
                for (var property : data) {
                    checkTime();
                    for (var value : reasoner.getDataPropertyValues(individual, property)) {
                        add(asserted, facts, df.getOWLDataPropertyAssertionAxiom(property, individual, value), round);
                    }
                }
            }
        } finally {
            reasoner.dispose();
        }
    }

    private void add(OWLOntology asserted, Map<OWLAxiom, DerivedFact> facts, OWLAxiom fact, int round) {
        checkTime();
        if (facts.containsKey(fact) || asserted.containsAxiom(fact, Imports.INCLUDED, AxiomAnnotations.IGNORE_AXIOM_ANNOTATIONS)) {
            return;
        }
        if (facts.size() >= limits.maxDerivedFacts()) {
            throw new DisplayStopped(Limit.DERIVED_FACTS, false);
        }
        facts.put(fact, new DerivedFact(fact, Origin.OWL_REASONER, round));
    }

    private List<DerivedFact> classify(OWLOntology asserted, Map<OWLAxiom, DerivedFact> facts)
            throws OWLOntologyCreationException {
        checkTime();
        var manager = OWLManager.createOWLOntologyManager();
        var standard = manager.createOntology();
        manager.addAxioms(standard, asserted.importsClosure().flatMap(OWLOntology::axioms)
                .filter(axiom -> !(axiom instanceof SWRLRule)));
        var reasoner = factory.apply(standard);
        try {
            checkTime();
            List<DerivedFact> classified = new ArrayList<>(facts.size());
            for (DerivedFact fact : facts.values()) {
                checkTime();
                var origin = reasoner.isEntailed(fact.axiom()) ? Origin.OWL_REASONER : Origin.RULE_ENGINE;
                classified.add(new DerivedFact(fact.axiom(), origin, fact.round()));
            }
            checkTime();
            return classified;
        } finally {
            reasoner.dispose();
        }
    }

    private static List<DerivedFact> withOrigin(Map<OWLAxiom, DerivedFact> facts, Origin origin) {
        return facts.values().stream().map(fact -> new DerivedFact(fact.axiom(), origin, fact.round())).toList();
    }

    private long elapsed() {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private void checkTime() {
        if (limits.cancelled().getAsBoolean()) {
            throw new DisplayStopped(Limit.NONE, true);
        }
        if (elapsed() >= limits.maxMillis()) {
            throw new DisplayStopped(Limit.TIME, false);
        }
    }

    private static final class DisplayStopped extends RuntimeException {
        final Limit limit;
        final boolean cancelled;

        DisplayStopped(Limit limit, boolean cancelled) {
            this.limit = limit;
            this.cancelled = cancelled;
        }
    }
}
