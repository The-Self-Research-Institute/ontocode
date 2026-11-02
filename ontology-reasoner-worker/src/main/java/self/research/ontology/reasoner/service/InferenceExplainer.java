package self.research.ontology.reasoner.service;

import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owl.explanation.impl.blackbox.EntailmentChecker;
import org.semanticweb.owl.explanation.impl.blackbox.checker.SatisfiabilityEntailmentCheckerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.reasoner.InconsistentOntologyException;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import self.research.ontology.common.SwrlBuiltIns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class InferenceExplainer {

    private static final Logger log = LoggerFactory.getLogger(InferenceExplainer.class);

    enum Status { EXPLAINED, ASSERTED, NOT_ENTAILED, UNSUPPORTED, TOO_LARGE, TIMEOUT, CANCELLED, FAILED }

    record Request(String axiomType, String subjectIri, String predicateIri, String objectIri,
                   String objectLiteral, String literalDatatypeIri, List<String> objectIris, String objectOperator) {

        Request(String axiomType, String subjectIri, String predicateIri, String objectIri,
                String objectLiteral, String literalDatatypeIri) {
            this(axiomType, subjectIri, predicateIri, objectIri, objectLiteral, literalDatatypeIri, null, null);
        }
    }

    record Outcome(Status status, OWLAxiom conclusion, List<OWLAxiom> axioms, boolean minimal, boolean partial,
                   String reasoner, boolean reasonerFallback, long elapsedMs, boolean budgetHit, String message,
                   int ontologyAxiomCount, boolean usesRules, int entailmentChecks) {
    }

    static final List<String> SUPPORTED_TYPES = List.of(
            "ClassAssertion", "SubClassOf", "ObjectPropertyAssertion", "DataPropertyAssertion",
            "ObjectPropertyDomain", "ObjectPropertyRange", "SubObjectPropertyOf", "InverseObjectProperties",
            "EquivalentClasses", "SameIndividual");

    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    private final OWLDataFactory df = OWLManager.getOWLDataFactory();
    private final long budgetMs;
    private final int maxAxioms;
    private final int maxAxiomsElk;

    InferenceExplainer(long budgetMs, int maxAxioms, int maxAxiomsElk) {
        this.budgetMs = budgetMs;
        this.maxAxioms = maxAxioms;
        this.maxAxiomsElk = maxAxiomsElk;
    }

    private static final class Stop extends RuntimeException {
        private final boolean cancelled;

        Stop(boolean cancelled) {
            super(cancelled ? "cancelled" : "time budget used up", null, false, false);
            this.cancelled = cancelled;
        }
    }

    private static final class Inconsistent extends RuntimeException {
        Inconsistent() {
            super("inconsistent", null, false, false);
        }
    }

    private interface Checker {
        boolean entails(Set<OWLAxiom> axioms);
    }

    private static final class Guard implements OWLReasonerFactory {
        private final OWLReasonerFactory delegate;
        private final long deadlineNanos;
        private final BooleanSupplier cancelled;
        private final Set<OWLReasoner> live = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
        private volatile boolean expired;
        private volatile boolean stopped;
        private volatile boolean finished;

        Guard(OWLReasonerFactory delegate, long deadlineNanos, BooleanSupplier cancelled) {
            this.delegate = delegate;
            this.deadlineNanos = deadlineNanos;
            this.cancelled = cancelled;
        }

        void check() {
            if (cancelled.getAsBoolean()) {
                stopped = true;
                throw new Stop(true);
            }
            if (System.nanoTime() - deadlineNanos > 0) {
                expired = true;
                throw new Stop(false);
            }
        }

        boolean wasCancelled() {
            return stopped;
        }

        boolean timedOut() {
            return expired;
        }

        void interruptAll() {
            List<OWLReasoner> snapshot;
            synchronized (live) {
                snapshot = new ArrayList<>(live);
            }
            for (OWLReasoner reasoner : snapshot) {
                try {
                    reasoner.interrupt();
                } catch (Exception ignored) {
                }
            }
        }

        void watch() {
            Thread watchdog = new Thread(() -> {
                while (!finished) {
                    boolean cancel = cancelled.getAsBoolean();
                    boolean late = System.nanoTime() - deadlineNanos > 0;
                    if (cancel || late) {
                        if (cancel) {
                            stopped = true;
                        } else {
                            expired = true;
                        }
                        while (!finished) {
                            interruptAll();
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                        return;
                    }
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "explain-inference-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }

        void finish() {
            finished = true;
        }

        private OWLReasoner track(OWLReasoner reasoner) {
            check();
            live.add(reasoner);
            return reasoner;
        }

        @Override
        public String getReasonerName() {
            return delegate.getReasonerName();
        }

        @Override
        public OWLReasoner createNonBufferingReasoner(OWLOntology ontology) {
            check();
            return track(delegate.createNonBufferingReasoner(ontology));
        }

        @Override
        public OWLReasoner createReasoner(OWLOntology ontology) {
            check();
            return track(delegate.createReasoner(ontology));
        }

        @Override
        public OWLReasoner createNonBufferingReasoner(OWLOntology ontology, OWLReasonerConfiguration config) {
            check();
            return track(delegate.createNonBufferingReasoner(ontology, config));
        }

        @Override
        public OWLReasoner createReasoner(OWLOntology ontology, OWLReasonerConfiguration config) {
            check();
            return track(delegate.createReasoner(ontology, config));
        }
    }

    private static final class UnsupportedFact extends RuntimeException {
        UnsupportedFact(String message) {
            super(message, null, false, false);
        }
    }

    Outcome explain(OWLOntology ontology, Request request, ReasonerType requested, BooleanSupplier cancelled) {
        long started = System.nanoTime();
        OWLAxiom conclusion;
        try {
            conclusion = conclusionFor(request);
        } catch (UnsupportedFact e) {
            return plain(Status.UNSUPPORTED, null, requested, started, e.getMessage(), 0);
        } catch (RuntimeException e) {
            return plain(Status.FAILED, null, requested, started,
                    "The fact to explain could not be read: " + e.getMessage(), 0);
        }

        List<OWLAxiom> pool = new ArrayList<>();
        List<SWRLRule> rules = new ArrayList<>();
        long builtInRules = 0;
        for (OWLAxiom axiom : ontology.getLogicalAxioms()) {
            if (axiom instanceof SWRLRule rule) {
                if (SwrlBuiltIns.usesBuiltIn(rule)) {
                    builtInRules++;
                } else {
                    rules.add(rule);
                }
            } else {
                pool.add(axiom);
            }
        }
        int total = pool.size() + rules.size();

        if (ontology.containsAxiomIgnoreAnnotations(conclusion)) {
            List<OWLAxiom> asserted = new ArrayList<>();
            ontology.getAxiomsIgnoreAnnotations(conclusion).stream().sorted().findFirst().ifPresent(asserted::add);
            if (asserted.isEmpty()) {
                asserted.add(conclusion);
            }
            return new Outcome(Status.ASSERTED, conclusion, asserted, true, false, ReasonerType.HERMIT.getDisplayName(),
                    false, elapsedMs(started), false, "This fact is written directly in the ontology.", total, false, 0);
        }

        Collections.sort(pool);
        rules.sort(null);

        long deadline = started + budgetMs * 1_000_000L;
        Run run = new Run(ontology, conclusion, pool, rules, builtInRules, total, started, deadline, cancelled);
        if (requested == ReasonerType.ELK && run.elkSupports(conclusion)) {
            if (total > maxAxiomsElk) {
                return run.tooLarge(ReasonerType.ELK, maxAxiomsElk);
            }
            Outcome viaElk = run.runElk();
            if (viaElk != null) {
                return viaElk;
            }
            return run.runHermit(true);
        }
        if (total > maxAxioms) {
            return run.tooLarge(ReasonerType.HERMIT, maxAxioms);
        }
        return run.runHermit(requested == ReasonerType.ELK);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private Outcome plain(Status status, OWLAxiom conclusion, ReasonerType reasoner, long started, String message, int total) {
        return new Outcome(status, conclusion, List.of(), false, false,
                (reasoner != null ? reasoner : ReasonerType.HERMIT).getDisplayName(), false, elapsedMs(started),
                false, message, total, false, 0);
    }

    OWLAxiom conclusionFor(Request request) {
        String type = request.axiomType() == null ? "" : request.axiomType().trim();
        return switch (type) {
            case "ClassAssertion" -> df.getOWLClassAssertionAxiom(
                    classOf(request, "class"),
                    df.getOWLNamedIndividual(require(request.subjectIri(), "individual")));
            case "SubClassOf" -> df.getOWLSubClassOfAxiom(
                    df.getOWLClass(require(request.subjectIri(), "subclass")),
                    classOf(request, "superclass"));
            case "ObjectPropertyAssertion" -> df.getOWLObjectPropertyAssertionAxiom(
                    df.getOWLObjectProperty(require(request.predicateIri(), "property")),
                    df.getOWLNamedIndividual(require(request.subjectIri(), "subject")),
                    df.getOWLNamedIndividual(require(request.objectIri(), "object")));
            case "DataPropertyAssertion" -> df.getOWLDataPropertyAssertionAxiom(
                    df.getOWLDataProperty(require(request.predicateIri(), "property")),
                    df.getOWLNamedIndividual(require(request.subjectIri(), "subject")),
                    literal(request));
            case "ObjectPropertyDomain" -> df.getOWLObjectPropertyDomainAxiom(
                    df.getOWLObjectProperty(require(request.subjectIri(), "property")),
                    df.getOWLClass(require(request.objectIri(), "class")));
            case "ObjectPropertyRange" -> df.getOWLObjectPropertyRangeAxiom(
                    df.getOWLObjectProperty(require(request.subjectIri(), "property")),
                    df.getOWLClass(require(request.objectIri(), "class")));
            case "SubObjectPropertyOf" -> df.getOWLSubObjectPropertyOfAxiom(
                    df.getOWLObjectProperty(require(request.subjectIri(), "sub-property")),
                    df.getOWLObjectProperty(require(request.objectIri(), "super-property")));
            case "InverseObjectProperties" -> df.getOWLInverseObjectPropertiesAxiom(
                    df.getOWLObjectProperty(require(request.subjectIri(), "property")),
                    df.getOWLObjectProperty(require(request.objectIri(), "inverse property")));
            case "EquivalentClasses" -> df.getOWLEquivalentClassesAxiom(
                    df.getOWLClass(require(request.subjectIri(), "class")),
                    df.getOWLClass(require(request.objectIri(), "equivalent class")));
            case "SameIndividual" -> df.getOWLSameIndividualAxiom(
                    df.getOWLNamedIndividual(require(request.subjectIri(), "individual")),
                    df.getOWLNamedIndividual(require(request.objectIri(), "other individual")));
            default -> throw new UnsupportedFact("Explaining a \"" + type + "\" fact is not supported yet. "
                    + "Supported kinds: " + String.join(", ", SUPPORTED_TYPES) + ".");
        };
    }

    private OWLClassExpression classOf(Request request, String what) {
        List<String> operands = request.objectIris();
        if (operands == null || operands.isEmpty()) {
            return df.getOWLClass(require(request.objectIri(), what));
        }
        if (operands.size() < 2) {
            throw new IllegalArgumentException("a union or intersection needs at least two classes");
        }
        Set<OWLClass> classes = new LinkedHashSet<>();
        for (String operand : operands) {
            classes.add(df.getOWLClass(require(operand, what)));
        }
        String operator = request.objectOperator() == null ? "" : request.objectOperator().trim().toUpperCase(java.util.Locale.ROOT);
        return switch (operator) {
            case "UNION" -> df.getOWLObjectUnionOf(classes);
            case "INTERSECTION" -> df.getOWLObjectIntersectionOf(classes);
            default -> throw new IllegalArgumentException("the operator must be UNION or INTERSECTION");
        };
    }

    private static IRI require(String iri, String what) {
        if (iri == null || iri.isBlank()) {
            throw new IllegalArgumentException("the " + what + " is missing");
        }
        return IRI.create(iri.trim());
    }

    private OWLLiteral literal(Request request) {
        if (request.objectLiteral() == null) {
            throw new IllegalArgumentException("the value is missing");
        }
        String datatype = request.literalDatatypeIri() == null || request.literalDatatypeIri().isBlank()
                ? XSD_STRING : request.literalDatatypeIri().trim();
        return df.getOWLLiteral(request.objectLiteral(), df.getOWLDatatype(IRI.create(datatype)));
    }

    private final class Run {
        private final OWLOntology source;
        private final OWLAxiom conclusion;
        private final List<OWLAxiom> pool;
        private final List<SWRLRule> rules;
        private final long builtInRules;
        private final int total;
        private final long started;
        private final long deadline;
        private final BooleanSupplier cancelled;
        private int checks;
        private List<OWLAxiom> bestKnown;
        private boolean bestMinimal;

        Run(OWLOntology source, OWLAxiom conclusion, List<OWLAxiom> pool, List<SWRLRule> rules, long builtInRules,
            int total, long started, long deadline, BooleanSupplier cancelled) {
            this.source = source;
            this.conclusion = conclusion;
            this.pool = pool;
            this.rules = rules;
            this.builtInRules = builtInRules;
            this.total = total;
            this.started = started;
            this.deadline = deadline;
            this.cancelled = cancelled;
        }

        Outcome tooLarge(ReasonerType type, int limit) {
            String message = "This ontology has " + total + " axioms, which is above the limit of " + limit
                    + " for explaining a fact with " + type.getDisplayName() + ". "
                    + (type == ReasonerType.ELK
                    ? "Narrow the ontology first, for example by working on a smaller project."
                    : "Try the faster ELK reasoner if the fact is of a kind it supports, or narrow the ontology first.");
            return new Outcome(Status.TOO_LARGE, conclusion, List.of(), false, false, type.getDisplayName(), false,
                    elapsedMs(started), false, message, total, false, 0);
        }

        boolean elkSupports(OWLAxiom axiom) {
            OWLReasoner probe = null;
            try {
                OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
                probe = new ElkReasonerFactory().createReasoner(manager.createOntology());
                return probe.isEntailmentCheckingSupported(axiom.getAxiomType());
            } catch (Throwable e) {
                return false;
            } finally {
                dispose(probe);
            }
        }

        Outcome runElk() {
            Guard guard = new Guard(new ElkReasonerFactory(), deadline, cancelled);
            guard.watch();
            try {
                Checker checker = direct(guard);
                List<OWLAxiom> found = search(checker, pool);
                if (found == null) {
                    return null;
                }
                return explained(ReasonerType.ELK, false, found, bestMinimal, false);
            } catch (Stop stop) {
                return stopped(ReasonerType.ELK, false, stop);
            } catch (Throwable e) {
                if (guard.wasCancelled() || guard.timedOut()) {
                    return stopped(ReasonerType.ELK, false, new Stop(guard.wasCancelled()));
                }
                log.warn("[Explain] ELK could not check this fact, falling back to HermiT: {}", e.getMessage());
                return null;
            } finally {
                guard.finish();
            }
        }

        Outcome runHermit(boolean fallback) {
            Guard guard = new Guard(new LenientHermitReasonerFactory(), deadline, cancelled);
            guard.watch();
            Supplier<OWLOntologyManager> managers = OWLManager::createOWLOntologyManager;
            try {
                EntailmentChecker<OWLAxiom> lib = null;
                try {
                    lib = new SatisfiabilityEntailmentCheckerFactory(guard, managers).createEntailementChecker(conclusion);
                } catch (RuntimeException e) {
                    if (!unsupportedByLibrary(e)) {
                        throw e;
                    }
                }
                EntailmentChecker<OWLAxiom> library = lib;
                Checker backup = direct(guard);
                boolean[] switched = {library == null};
                Checker checker = axioms -> {
                    if (switched[0]) {
                        return backup.entails(axioms);
                    }
                    guard.check();
                    checks++;
                    try {
                        return library.isEntailed(axioms);
                    } catch (InconsistentOntologyException e) {
                        throw new Inconsistent();
                    } catch (ReasonerInterruptedException e) {
                        guard.check();
                        throw new Stop(guard.wasCancelled());
                    } catch (RuntimeException e) {
                        if (e instanceof Stop || !unsupportedByLibrary(e)) {
                            throw e;
                        }
                        switched[0] = true;
                        return backup.entails(axioms);
                    }
                };
                if (checker.entails(Set.of())) {
                    return explained(ReasonerType.HERMIT, fallback, List.of(), true, false);
                }
                List<OWLAxiom> found = search(checker, pool);
                if (found == null && !rules.isEmpty()) {
                    List<OWLAxiom> withRules = new ArrayList<>(pool);
                    withRules.addAll(rules);
                    found = search(checker, withRules);
                }
                if (found == null) {
                    return notEntailed(fallback);
                }
                return explained(ReasonerType.HERMIT, fallback, found, bestMinimal, false);
            } catch (Stop stop) {
                return stopped(ReasonerType.HERMIT, fallback, stop);
            } catch (Inconsistent inconsistent) {
                return new Outcome(Status.FAILED, conclusion, List.of(), false, false,
                        ReasonerType.HERMIT.getDisplayName(), fallback, elapsedMs(started), false,
                        "This ontology is inconsistent, so every statement follows from it. "
                                + "Explain the inconsistency first.", total, false, checks);
            } catch (Throwable e) {
                if (guard.wasCancelled() || guard.timedOut()) {
                    return stopped(ReasonerType.HERMIT, fallback, new Stop(guard.wasCancelled()));
                }
                log.warn("[Explain] Could not explain {}: {}", conclusion, e.toString());
                return new Outcome(Status.FAILED, conclusion, List.of(), false, false,
                        ReasonerType.HERMIT.getDisplayName(), fallback, elapsedMs(started), false,
                        "The explanation could not be computed: " + rootMessage(e), total, false, checks);
            } finally {
                guard.finish();
            }
        }

        private boolean unsupportedByLibrary(Throwable error) {
            for (Throwable t = error; t != null; t = t.getCause()) {
                if (t.getClass().getSimpleName().startsWith("UnsupportedAxiomType")) {
                    return true;
                }
            }
            return false;
        }

        private Checker direct(Guard guard) {
            return axioms -> {
                guard.check();
                checks++;
                OWLReasoner reasoner = null;
                OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
                try {
                    OWLOntology subset = manager.createOntology(axioms);
                    reasoner = guard.createReasoner(subset);
                    return reasoner.isEntailed(conclusion);
                } catch (InconsistentOntologyException e) {
                    throw new Inconsistent();
                } catch (ReasonerInterruptedException e) {
                    guard.check();
                    throw new Stop(guard.wasCancelled());
                } catch (OWLOntologyCreationException e) {
                    throw new IllegalStateException(e.getMessage(), e);
                } finally {
                    dispose(reasoner);
                }
            };
        }

        private Outcome notEntailed(boolean fallback) {
            String message = "The reasoner does not conclude this fact from the ontology, so there is nothing to explain.";
            if (builtInRules > 0) {
                message += " " + builtInRules + (builtInRules == 1 ? " rule uses" : " rules use")
                        + " built-in functions, which this explanation does not follow. "
                        + "If the fact came from one of those rules, it cannot be explained here.";
            }
            return new Outcome(Status.NOT_ENTAILED, conclusion, List.of(), false, false,
                    ReasonerType.HERMIT.getDisplayName(), fallback, elapsedMs(started), false, message, total, false, checks);
        }

        private Outcome explained(ReasonerType type, boolean fallback, List<OWLAxiom> axioms, boolean minimal, boolean budgetHit) {
            boolean usesRules = axioms.stream().anyMatch(a -> a instanceof SWRLRule);
            String message = axioms.isEmpty()
                    ? "This fact is true in every ontology, so no axioms are needed."
                    : minimal ? "These axioms are enough to conclude this fact, and removing any one of them breaks it."
                    : "These axioms are enough to conclude this fact, but the search stopped before checking that each is needed.";
            return new Outcome(Status.EXPLAINED, conclusion, axioms, minimal, !minimal, type.getDisplayName(), fallback,
                    elapsedMs(started), budgetHit, message, total, usesRules, checks);
        }

        private Outcome stopped(ReasonerType type, boolean fallback, Stop stop) {
            if (stop.cancelled) {
                return new Outcome(Status.CANCELLED, conclusion, List.of(), false, false, type.getDisplayName(), fallback,
                        elapsedMs(started), false, "The explanation was cancelled.", total, false, checks);
            }
            if (bestKnown != null) {
                return explained(type, fallback, bestKnown, false, true);
            }
            return new Outcome(Status.TIMEOUT, conclusion, List.of(), false, false, type.getDisplayName(), fallback,
                    elapsedMs(started), true, "The explanation took longer than the " + (budgetMs / 1000)
                    + " second limit. Try the faster ELK reasoner if this kind of fact is supported, "
                    + "or narrow the ontology.", total, false, checks);
        }

        private List<OWLAxiom> search(Checker checker, List<OWLAxiom> candidates) {
            bestKnown = null;
            bestMinimal = false;
            if (candidates.isEmpty()) {
                return null;
            }
            Set<OWLEntity> seed = relevant(conclusion);
            List<List<List<OWLAxiom>>> strategies = new ArrayList<>();
            Index index = new Index(candidates);
            List<List<OWLAxiom>> focused = layers(index, seed, true);
            int reached = count(focused);
            strategies.add(focused);
            List<List<OWLAxiom>> open = layers(index, seed, false);
            if (count(open) > reached) {
                strategies.add(open);
                reached = count(open);
            }
            if (candidates.size() > reached) {
                strategies.add(List.of(candidates));
            }
            for (List<List<OWLAxiom>> layers : strategies) {
                List<OWLAxiom> found = attempt(checker, layers);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        private int count(List<List<OWLAxiom>> layers) {
            return layers.stream().mapToInt(List::size).sum();
        }

        private List<OWLAxiom> attempt(Checker checker, List<List<OWLAxiom>> layers) {
            if (layers.isEmpty()) {
                return null;
            }
            List<OWLAxiom> everything = flatten(layers, layers.size());
            if (!checker.entails(new LinkedHashSet<>(everything))) {
                return null;
            }
            bestKnown = everything;
            int low = 1;
            int high = layers.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                List<OWLAxiom> prefix = flatten(layers, mid);
                if (checker.entails(new LinkedHashSet<>(prefix))) {
                    high = mid;
                    bestKnown = prefix;
                } else {
                    low = mid + 1;
                }
            }
            List<OWLAxiom> narrowed = flatten(layers, high);
            bestKnown = narrowed;
            List<OWLAxiom> minimal = minimise(checker, narrowed);
            bestKnown = minimal;
            bestMinimal = true;
            return minimal;
        }

        private List<OWLAxiom> flatten(List<List<OWLAxiom>> layers, int count) {
            List<OWLAxiom> out = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                out.addAll(layers.get(i));
            }
            return out;
        }

        private List<OWLAxiom> minimise(Checker checker, List<OWLAxiom> support) {
            if (support.size() <= 1) {
                return new ArrayList<>(support);
            }
            return quickXplain(checker, new LinkedHashSet<>(), false, support);
        }

        private List<OWLAxiom> quickXplain(Checker checker, Set<OWLAxiom> background, boolean addedToBackground,
                                           List<OWLAxiom> candidates) {
            if (addedToBackground && checker.entails(background)) {
                return new ArrayList<>();
            }
            if (candidates.size() == 1) {
                return new ArrayList<>(candidates);
            }
            int half = candidates.size() / 2;
            List<OWLAxiom> first = candidates.subList(0, half);
            List<OWLAxiom> second = candidates.subList(half, candidates.size());
            Set<OWLAxiom> withFirst = new LinkedHashSet<>(background);
            withFirst.addAll(first);
            List<OWLAxiom> fromSecond = quickXplain(checker, withFirst, true, second);
            Set<OWLAxiom> withSecondPart = new LinkedHashSet<>(background);
            withSecondPart.addAll(fromSecond);
            List<OWLAxiom> fromFirst = quickXplain(checker, withSecondPart, !fromSecond.isEmpty(), first);
            List<OWLAxiom> out = new ArrayList<>(fromFirst);
            out.addAll(fromSecond);
            return out;
        }

        private final class Index {
            final List<OWLAxiom> candidates;
            final Map<OWLEntity, List<Integer>> byEntity = new HashMap<>();
            final List<Set<OWLEntity>> signatures;
            final boolean[] assertion;

            Index(List<OWLAxiom> candidates) {
                this.candidates = candidates;
                this.signatures = new ArrayList<>(candidates.size());
                this.assertion = new boolean[candidates.size()];
                for (int i = 0; i < candidates.size(); i++) {
                    OWLAxiom axiom = candidates.get(i);
                    assertion[i] = isAssertion(axiom);
                    Set<OWLEntity> signature = relevant(axiom);
                    signatures.add(signature);
                    for (OWLEntity entity : signature) {
                        byEntity.computeIfAbsent(entity, key -> new ArrayList<>(2)).add(i);
                    }
                }
            }
        }

        private List<List<OWLAxiom>> layers(Index index, Set<OWLEntity> seed, boolean focused) {
            Set<OWLEntity> known = new HashSet<>(seed);
            Set<OWLEntity> frontier = new LinkedHashSet<>(seed);
            boolean[] taken = new boolean[index.candidates.size()];
            List<List<OWLAxiom>> layers = new ArrayList<>();
            while (!frontier.isEmpty()) {
                TreeSet<Integer> next = new TreeSet<>();
                for (OWLEntity entity : frontier) {
                    List<Integer> hits = index.byEntity.get(entity);
                    if (hits == null) {
                        continue;
                    }
                    for (int hit : hits) {
                        if (taken[hit]) {
                            continue;
                        }
                        if (focused && index.assertion[hit] && !entity.isOWLNamedIndividual()) {
                            continue;
                        }
                        next.add(hit);
                    }
                }
                if (next.isEmpty()) {
                    break;
                }
                List<OWLAxiom> layer = new ArrayList<>();
                Set<OWLEntity> grown = new LinkedHashSet<>();
                for (int hit : next) {
                    taken[hit] = true;
                    layer.add(index.candidates.get(hit));
                    for (OWLEntity entity : index.signatures.get(hit)) {
                        if (known.add(entity)) {
                            grown.add(entity);
                        }
                    }
                }
                layers.add(layer);
                frontier = grown;
            }
            return layers;
        }

        private boolean isAssertion(OWLAxiom axiom) {
            return axiom.isOfType(AxiomType.CLASS_ASSERTION, AxiomType.OBJECT_PROPERTY_ASSERTION,
                    AxiomType.DATA_PROPERTY_ASSERTION, AxiomType.NEGATIVE_OBJECT_PROPERTY_ASSERTION,
                    AxiomType.NEGATIVE_DATA_PROPERTY_ASSERTION, AxiomType.SAME_INDIVIDUAL,
                    AxiomType.DIFFERENT_INDIVIDUALS);
        }
    }

    private static Set<OWLEntity> relevant(OWLAxiom axiom) {
        Set<OWLEntity> out = new LinkedHashSet<>();
        for (OWLEntity entity : axiom.getAxiomWithoutAnnotations().getSignature()) {
            if (entity.isTopEntity() || entity.isBottomEntity() || entity.isOWLAnnotationProperty()
                    || (entity.isOWLDatatype() && entity.asOWLDatatype().isBuiltIn())) {
                continue;
            }
            out.add(entity);
        }
        return out;
    }

    private static void dispose(OWLReasoner reasoner) {
        if (reasoner == null) {
            return;
        }
        try {
            reasoner.dispose();
        } catch (Exception ignored) {
        }
    }

    static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
