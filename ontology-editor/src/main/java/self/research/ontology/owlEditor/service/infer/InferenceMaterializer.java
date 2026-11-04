package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.model.Statement;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException;
import self.research.ontology.common.RuleStage;
import self.research.ontology.common.rules.RuleExecution;
import self.research.ontology.common.rules.RuleExecutionLimits;
import self.research.ontology.common.rules.RuleExecutionResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class InferenceMaterializer {

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "inference-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final RuleStage ruleStage;
    private final int maxGroup;
    private final int maxEqualityTriples;

    public InferenceMaterializer(RuleStage ruleStage, int maxGroup, int maxEqualityTriples) {
        this.ruleStage = ruleStage;
        this.maxGroup = maxGroup;
        this.maxEqualityTriples = maxEqualityTriples;
    }

    public Materialization materialize(OWLOntology asserted, long timeLimitMillis) {
        Deadline deadline = new Deadline(timeLimitMillis);
        Map<String, Long> steps = new LinkedHashMap<>();
        long t = System.nanoTime();
        List<String> reasons = new ArrayList<>();
        Map<String, String> versions = new LinkedHashMap<>();
        versions.put("owlReasoner", InferenceProfile.REASONER_VERSION);
        versions.put("ruleEngine", ruleStage == null ? "none" : ruleStage.describe());
        OWLOntology working = asserted;
        List<Statement> ruleFacts = new ArrayList<>();
        if (asserted.axioms(AxiomType.SWRL_RULE).findAny().isPresent()) {
            RuleExecutionLimits limits = new RuleExecutionLimits(InferenceProfile.RULE_MAX_ROUNDS,
                    Math.max(1L, deadline.remainingMillis()), InferenceProfile.RULE_MAX_DERIVED, deadline::expired);
            RuleExecutionResult result = new RuleExecution(ruleStage,
                    input -> new ReasonerFactory().createReasoner(input), InferenceProfile.REASONER_VERSION)
                    .run(asserted, "inference", limits);
            versions.putAll(result.engineVersions());
            switch (result.status()) {
                case INCONSISTENT -> throw inconsistent(result.working() != null ? result.working() : asserted);
                case FAILED -> throw new IllegalStateException(result.message());
                case CANCELLED -> deadline.check();
                case COMPLETED -> { }
                default -> reasons.add("Rules: " + result.message());
            }
            result.limitations().stream().filter(l -> l.contains("sameAs")).forEach(l -> reasons.add("Rules: " + l));
            if (result.working() != null) {
                working = result.working();
            }
            for (RuleExecutionResult.DerivedFact fact : result.derived()) {
                ruleFacts.addAll(statementsOf(fact.axiom()));
            }
        }
        steps.put("rules", (System.nanoTime() - t) / 1_000_000L);
        t = System.nanoTime();
        ExtractedFacts extracted = reason(working, deadline, steps);
        steps.put("reasonAndExtract", (System.nanoTime() - t) / 1_000_000L);
        t = System.nanoTime();
        extracted.facts.addAll(ruleFacts);
        EqualityExpander.Result expanded = new EqualityExpander(maxEqualityTriples, maxGroup).expand(extracted, reasons);
        steps.put("equality", (System.nanoTime() - t) / 1_000_000L);
        Set<Statement> inferred = new LinkedHashSet<>(extracted.structure);
        inferred.addAll(expanded.statements());
        return new Materialization(inferred, ruleFacts.size(), List.copyOf(reasons),
                new Materialization.EqualityMetrics(extracted.groupCount, extracted.largestGroup,
                        expanded.triplesAdded(), expanded.incomplete()), versions, steps);
    }

    private ExtractedFacts reason(OWLOntology working, Deadline deadline, Map<String, Long> steps) {
        long started = System.nanoTime();
        OWLReasoner reasoner = new ReasonerFactory().createReasoner(working);
        ScheduledFuture<?> guard = WATCHDOG.schedule(reasoner::interrupt, deadline.remainingMillis(), TimeUnit.MILLISECONDS);
        try {
            if (!reasoner.isConsistent()) {
                throw inconsistent(working);
            }
            steps.put("hermitConsistency", (System.nanoTime() - started) / 1_000_000L);
            return new OwlFactExtractor(working, reasoner, deadline, maxGroup).extract();
        } catch (ReasonerInterruptedException e) {
            deadline.check();
            throw new InferenceException(InferenceException.TIMEOUT, "The reasoner was interrupted.");
        } finally {
            guard.cancel(false);
            reasoner.dispose();
        }
    }

    private static InconsistentOntologyException inconsistent(OWLOntology ontology) {
        return new InconsistentOntologyException("The ontology is inconsistent, so no inferred view is served.",
                InconsistencyExplainer.explain(ontology));
    }

    private static List<Statement> statementsOf(OWLAxiom raw) {
        OWLAxiom axiom = raw.getAxiomWithoutAnnotations();
        List<Statement> out = new ArrayList<>();
        if (axiom instanceof OWLClassAssertionAxiom a && !a.getClassExpression().isAnonymous() && a.getIndividual().isNamed()) {
            out.add(RdfTerms.triple(RdfTerms.iri(a.getIndividual().asOWLNamedIndividual()), RdfTerms.TYPE,
                    RdfTerms.iri(a.getClassExpression().asOWLClass())));
        } else if (axiom instanceof OWLObjectPropertyAssertionAxiom a && !a.getProperty().isAnonymous()
                && a.getSubject().isNamed() && a.getObject().isNamed()) {
            out.add(RdfTerms.triple(RdfTerms.iri(a.getSubject().asOWLNamedIndividual()),
                    RdfTerms.iri(a.getProperty().asOWLObjectProperty()), RdfTerms.iri(a.getObject().asOWLNamedIndividual())));
        } else if (axiom instanceof OWLDataPropertyAssertionAxiom a && !a.getProperty().isAnonymous() && a.getSubject().isNamed()) {
            out.add(RdfTerms.triple(RdfTerms.iri(a.getSubject().asOWLNamedIndividual()),
                    RdfTerms.iri(a.getProperty().asOWLDataProperty()), RdfTerms.literal(a.getObject())));
        }
        return out;
    }
}
