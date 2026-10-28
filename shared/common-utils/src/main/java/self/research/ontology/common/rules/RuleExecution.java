package self.research.ontology.common.rules;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.ClassExpressionType;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.SWRLClassAtom;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLDifferentIndividualsAtom;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.model.SWRLSameIndividualAtom;
import org.semanticweb.owlapi.model.parameters.AxiomAnnotations;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import self.research.ontology.common.RestRuleStage;
import self.research.ontology.common.RuleStage;
import self.research.ontology.common.SwrlBuiltIns;
import self.research.ontology.common.SwrlComparisonTranslator;
import self.research.ontology.common.rules.RuleExecutionResult.DerivedFact;
import self.research.ontology.common.rules.RuleExecutionResult.Limit;
import self.research.ontology.common.rules.RuleExecutionResult.Origin;
import self.research.ontology.common.rules.RuleExecutionResult.SkippedRule;
import self.research.ontology.common.rules.RuleExecutionResult.Status;
import self.research.ontology.common.rules.RuleExecutionResult.Stopping;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class RuleExecution {

    private final RuleStage stage;
    private final Function<OWLOntology, OWLReasoner> reasonerFactory;
    private final String reasonerName;

    public RuleExecution(RuleStage stage, Function<OWLOntology, OWLReasoner> reasonerFactory, String reasonerName) {
        this.stage = stage;
        this.reasonerFactory = reasonerFactory;
        this.reasonerName = reasonerName;
    }

    public RuleExecutionResult run(OWLOntology asserted, String snapshotId, RuleExecutionLimits limits) {
        long started = System.nanoTime();
        Map<String, String> versions = new TreeMap<>();
        versions.put("owlReasoner", reasonerName);
        versions.put("ruleEngine", stage == null ? "none" : stage.describe());
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology working;
        try {
            working = manager.createOntology();
        } catch (OWLOntologyCreationException e) {
            return failed(null, snapshotId, versions, "Could not create the working copy: " + e.getMessage(), limits, started, 0, List.of(), List.of(), List.of());
        }
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntologyManager baseManager = OWLManager.createOWLOntologyManager();
        OWLOntology engineBase;
        try {
            engineBase = baseManager.createOntology();
        } catch (OWLOntologyCreationException e) {
            return failed(null, snapshotId, versions, "Could not create the working copy: " + e.getMessage(), limits, started, 0, List.of(), List.of(), List.of());
        }
        List<OWLAxiom> assertedAxioms = asserted.importsClosure()
                .flatMap(OWLOntology::axioms)
                .filter(axiom -> !(axiom instanceof SWRLRule))
                .collect(Collectors.toList());
        manager.addAxioms(working, assertedAxioms);
        baseManager.addAxioms(engineBase, assertedAxioms);
        List<SWRLRule> rules = asserted.importsClosure()
                .flatMap(o -> o.axioms(AxiomType.SWRL_RULE))
                .distinct()
                .sorted(Comparator.comparing(RuleExecution::canonical))
                .collect(Collectors.toList());

        int translatedCount = 0;
        List<String> executed = new ArrayList<>();
        List<String> executedIds = new ArrayList<>();
        List<SkippedRule> skipped = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        List<SWRLRule> engineRules = new ArrayList<>();
        List<SWRLRule> headRules = new ArrayList<>();
        Map<SWRLRule, String> ids = new LinkedHashMap<>();
        Map<SWRLRule, String> names = new LinkedHashMap<>();
        for (SWRLRule rule : rules) {
            String id = ruleId(rule);
            ids.put(rule, id);
            names.put(rule, ruleName(rule));
            if (SwrlBuiltIns.usesBuiltIn(rule)) {
                Optional<OWLAxiom> translated = SwrlComparisonTranslator.translate(rule, df);
                if (translated.isPresent()) {
                    manager.addAxiom(working, translated.get());
                    translatedCount++;
                    executed.add(names.get(rule));
                    executedIds.add(id);
                    headRules.add(rule);
                } else if (concludesEquality(rule)) {
                    skipped.add(new SkippedRule(names.get(rule), id, "A rule that uses a built-in function and concludes sameAs or differentFrom is not supported."));
                } else {
                    engineRules.add(rule);
                    headRules.add(rule);
                }
            } else if (bodyUsesDifferentFrom(rule)) {
                engineRules.add(rule);
                headRules.add(rule);
            } else {
                manager.addAxiom(working, rule);
                executed.add(names.get(rule));
                executedIds.add(id);
                headRules.add(rule);
            }
        }
        if (!engineRules.isEmpty() && stage == null) {
            for (SWRLRule rule : engineRules) {
                skipped.add(new SkippedRule(names.get(rule), ids.get(rule), "No rule engine is available here."));
            }
            engineRules.clear();
        }

        if (asserted.importsClosure().anyMatch(o -> o.anonymousIndividuals().findAny().isPresent())) {
            limitations.add("The ontology has unnamed (anonymous) individuals. Rules only apply to named individuals, and conclusions about unnamed ones are not transferred.");
        }
        if (!engineRules.isEmpty() && hasEqualityAxioms(working)) {
            limitations.add("Equality (sameAs, differentFrom, functional properties) found by OWL reasoning is not passed to the rule engine, so rules that depend on it may miss conclusions.");
        }

        Set<org.semanticweb.owlapi.model.OWLEntity> engineInputs = new LinkedHashSet<>();
        for (SWRLRule rule : engineRules) {
            rule.body().forEach(atom -> atom.signature().forEach(engineInputs::add));
        }
        Set<OWLClass> classes = new LinkedHashSet<>();
        Set<OWLObjectProperty> objectProperties = new LinkedHashSet<>();
        Set<OWLDataProperty> dataProperties = new LinkedHashSet<>();
        for (SWRLRule rule : engineRules) {
            rule.body().forEach(atom -> collect(atom, classes, objectProperties, dataProperties));
        }
        for (SWRLRule rule : headRules) {
            rule.head().forEach(atom -> collect(atom, classes, objectProperties, dataProperties));
        }
        classes.remove(df.getOWLThing());
        classes.remove(df.getOWLNothing());

        Map<String, DerivedFact> derived = new LinkedHashMap<>();
        boolean engineAvailable = true;
        String busyNote = null;
        boolean engineRan = false;
        int round = 0;
        Limit limit = Limit.NONE;
        boolean converged = false;
        Status forced = null;
        String message = null;
        long[] ignored = new long[2];
        try {
            while (true) {
                if (limits.cancelled().getAsBoolean()) {
                    forced = Status.CANCELLED;
                    break;
                }
                if (elapsedMillis(started) > limits.maxMillis()) {
                    limit = Limit.TIME;
                    break;
                }
                if (round >= limits.maxRounds()) {
                    limit = Limit.ROUNDS;
                    break;
                }
                round++;
                if (!engineRules.isEmpty() && engineAvailable) {
                    Optional<Set<OWLAxiom>> out = runEngine(baseManager, engineBase, engineRules);
                    if (out.isEmpty()) {
                        engineAvailable = false;
                        busyNote = RestRuleStage.takeBusyLimitation();
                    } else {
                        engineRan = true;
                        absorb(out.get(), working, manager, engineBase, baseManager, derived, Origin.RULE_ENGINE, round, ignored, engineInputs);
                    }
                }
                if (derived.size() > limits.maxDerivedFacts()) {
                    limit = Limit.DERIVED_FACTS;
                    break;
                }
                if (limits.cancelled().getAsBoolean()) {
                    forced = Status.CANCELLED;
                    break;
                }
                Optional<Set<OWLAxiom>> owlFacts = owlFacts(working, df, classes, objectProperties, dataProperties);
                if (owlFacts.isEmpty()) {
                    forced = Status.INCONSISTENT;
                    break;
                }
                int added = absorb(owlFacts.get(), working, manager, engineBase, baseManager, derived, Origin.OWL_REASONER, round, ignored, engineInputs);
                if (derived.size() > limits.maxDerivedFacts()) {
                    limit = Limit.DERIVED_FACTS;
                    break;
                }
                if (added == 0 || engineRules.isEmpty() || !engineAvailable) {
                    converged = true;
                    break;
                }
            }
        } catch (RuntimeException | StackOverflowError e) {
            forced = Status.FAILED;
            message = "Reasoning failed: " + String.valueOf(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }

        if (ignored[0] > 0) {
            limitations.add(ignored[0] + " conclusion" + (ignored[0] == 1 ? "" : "s") + " about unnamed individuals or class expressions " + (ignored[0] == 1 ? "was" : "were") + " not transferred.");
        }
        if (ignored[1] > 0) {
            limitations.add(ignored[1] + " derived sameAs or differentFrom conclusion" + (ignored[1] == 1 ? "" : "s") + " from the rule engine " + (ignored[1] == 1 ? "was" : "were") + " not transferred.");
        }
        if (!engineAvailable) {
            String skipReason = busyNote != null ? "The rule engine is busy." : "The rule engine could not be reached.";
            for (SWRLRule rule : engineRules) {
                skipped.add(new SkippedRule(names.get(rule), ids.get(rule), skipReason));
            }
            if (busyNote != null) {
                limitations.add(busyNote);
            }
        } else if (engineRan || engineRules.isEmpty()) {
            for (SWRLRule rule : engineRules) {
                executed.add(names.get(rule));
                executedIds.add(ids.get(rule));
            }
        }

        Status status;
        if (forced != null) {
            status = forced;
        } else if (limit != Limit.NONE) {
            status = Status.PARTIAL_LIMIT_REACHED;
        } else if (!engineAvailable && !engineRules.isEmpty()) {
            status = Status.PARTIAL_ENGINE_UNAVAILABLE;
        } else if (!skipped.isEmpty()) {
            status = Status.PARTIAL_RULES_SKIPPED;
        } else {
            status = Status.COMPLETED;
        }
        if (message == null) {
            message = busyNote != null && status == Status.PARTIAL_ENGINE_UNAVAILABLE
                    ? "The rule engine is busy, so rules that need it did not run. Try again in a moment. OWL reasoning continued without them; their conclusions are unknown, not false."
                    : describe(status, limit, skipped.size(), round, limits);
        }
        Stopping stopping = new Stopping(round, limits.maxRounds(), elapsedMillis(started), limits.maxMillis(), limit, converged);
        return new RuleExecutionResult(status, working, List.copyOf(derived.values()), List.copyOf(executed), List.copyOf(executedIds), engineRan ? engineRules.size() : 0, translatedCount,
                List.copyOf(skipped), List.copyOf(limitations), snapshotId, versions, stopping, message);
    }

    private RuleExecutionResult failed(OWLOntology working, String snapshotId, Map<String, String> versions, String message,
                                       RuleExecutionLimits limits, long started, int rounds, List<String> executed,
                                       List<SkippedRule> skipped, List<String> limitations) {
        return new RuleExecutionResult(Status.FAILED, working, List.of(), executed, executed, 0, 0, skipped, limitations, snapshotId, versions,
                new Stopping(rounds, limits.maxRounds(), elapsedMillis(started), limits.maxMillis(), Limit.NONE, false), message);
    }

    private static String describe(Status status, Limit limit, int skipped, int rounds, RuleExecutionLimits limits) {
        return switch (status) {
            case COMPLETED -> "Reached a stable result after " + rounds + " round" + (rounds == 1 ? "" : "s") + " within the supported rule profile.";
            case PARTIAL_LIMIT_REACHED -> "Stopped early: " + switch (limit) {
                case ROUNDS -> "the limit of " + limits.maxRounds() + " rounds was reached";
                case TIME -> "the time limit of " + limits.maxMillis() / 1000 + " seconds was reached";
                case DERIVED_FACTS -> "more than " + limits.maxDerivedFacts() + " new facts were produced";
                default -> "a limit was reached";
            } + ". The result may be missing conclusions.";
            case PARTIAL_ENGINE_UNAVAILABLE -> "The rule engine could not be reached, so rules that need it did not run. OWL reasoning continued without them; their conclusions are unknown, not false.";
            case PARTIAL_RULES_SKIPPED -> skipped + " rule" + (skipped == 1 ? " was" : "s were") + " skipped. Their conclusions are unknown, not false.";
            case INCONSISTENT -> "The ontology is inconsistent, so no further conclusions were drawn.";
            case CANCELLED -> "Cancelled before finishing.";
            case FAILED -> "Reasoning failed.";
        };
    }

    private Optional<Set<OWLAxiom>> runEngine(OWLOntologyManager manager, OWLOntology working, List<SWRLRule> engineRules) {
        manager.addAxioms(working, engineRules);
        try {
            Optional<Set<OWLAxiom>> out = stage.infer(working, engineRules);
            return out == null ? Optional.empty() : out;
        } catch (RuntimeException e) {
            return Optional.empty();
        } finally {
            manager.removeAxioms(working, engineRules);
        }
    }

    private Optional<Set<OWLAxiom>> owlFacts(OWLOntology working, OWLDataFactory df, Set<OWLClass> classes,
                                             Set<OWLObjectProperty> objectProperties, Set<OWLDataProperty> dataProperties) {
        boolean mayMerge = mayMergeIndividuals(working);
        Set<OWLClass> openClasses = new LinkedHashSet<>();
        for (OWLClass c : classes) {
            if (mayMerge || !onlyAsserted(working, c)) {
                openClasses.add(c);
            }
        }
        List<OWLObjectProperty> openObjectProperties = new ArrayList<>();
        for (OWLObjectProperty p : objectProperties) {
            if (mayMerge || !onlyAsserted(working, p)) {
                openObjectProperties.add(p);
            }
        }
        List<OWLDataProperty> openDataProperties = new ArrayList<>();
        for (OWLDataProperty d : dataProperties) {
            if (mayMerge || !onlyAsserted(working, d)) {
                openDataProperties.add(d);
            }
        }
        OWLReasoner reasoner = reasonerFactory.apply(working);
        try {
            if (!reasoner.isConsistent()) {
                return Optional.empty();
            }
            List<InferenceType> precompute = new ArrayList<>();
            if (!openClasses.isEmpty()) {
                precompute.add(InferenceType.CLASS_ASSERTIONS);
            }
            if (!openObjectProperties.isEmpty()) {
                precompute.add(InferenceType.OBJECT_PROPERTY_ASSERTIONS);
            }
            if (!precompute.isEmpty()) {
                reasoner.precomputeInferences(precompute.toArray(new InferenceType[0]));
            }
            Set<OWLAxiom> facts = new LinkedHashSet<>();
            for (OWLClass c : openClasses) {
                reasoner.getInstances(c, false).entities().forEach(i -> facts.add(df.getOWLClassAssertionAxiom(c, i)));
            }
            if (!openObjectProperties.isEmpty() || !openDataProperties.isEmpty()) {
                List<OWLNamedIndividual> individuals = working.individualsInSignature().collect(Collectors.toList());
                for (OWLNamedIndividual i : individuals) {
                    for (OWLObjectProperty p : openObjectProperties) {
                        reasoner.getObjectPropertyValues(i, p).entities()
                                .filter(OWLNamedIndividual.class::isInstance)
                                .forEach(o -> facts.add(df.getOWLObjectPropertyAssertionAxiom(p, i, o)));
                    }
                    for (OWLDataProperty d : openDataProperties) {
                        reasoner.getDataPropertyValues(i, d).forEach(v -> facts.add(df.getOWLDataPropertyAssertionAxiom(d, i, v)));
                    }
                }
            }
            return Optional.of(facts);
        } finally {
            reasoner.dispose();
        }
    }

    private static final Set<ClassExpressionType> MONOTONE = Set.of(ClassExpressionType.OWL_CLASS, ClassExpressionType.OBJECT_INTERSECTION_OF,
            ClassExpressionType.OBJECT_UNION_OF, ClassExpressionType.OBJECT_SOME_VALUES_FROM, ClassExpressionType.DATA_SOME_VALUES_FROM,
            ClassExpressionType.OBJECT_HAS_VALUE, ClassExpressionType.DATA_HAS_VALUE, ClassExpressionType.OBJECT_MIN_CARDINALITY,
            ClassExpressionType.DATA_MIN_CARDINALITY);

    private static boolean monotone(OWLClassExpression expression) {
        return expression.nestedClassExpressions().allMatch(e -> MONOTONE.contains(e.getClassExpressionType()));
    }

    private static boolean mayMergeIndividuals(OWLOntology ontology) {
        return ontology.axioms().anyMatch(axiom -> {
            if (axiom instanceof OWLClassAssertionAxiom a && a.getClassExpression() instanceof OWLClass) {
                return false;
            }
            if (axiom instanceof OWLObjectPropertyAssertionAxiom || axiom instanceof OWLDataPropertyAssertionAxiom
                    || axiom instanceof OWLDeclarationAxiom || axiom instanceof OWLAnnotationAssertionAxiom) {
                return false;
            }
            if (axiom.isOfType(AxiomType.SAME_INDIVIDUAL, AxiomType.FUNCTIONAL_OBJECT_PROPERTY,
                    AxiomType.INVERSE_FUNCTIONAL_OBJECT_PROPERTY, AxiomType.HAS_KEY)) {
                return true;
            }
            if (axiom instanceof SWRLRule r && r.head().anyMatch(a -> a instanceof SWRLSameIndividualAtom || a instanceof SWRLDifferentIndividualsAtom)) {
                return true;
            }
            return axiom.nestedClassExpressions().anyMatch(e -> switch (e.getClassExpressionType()) {
                case OBJECT_ONE_OF, OBJECT_MAX_CARDINALITY, OBJECT_EXACT_CARDINALITY -> true;
                default -> false;
            });
        });
    }

    private static boolean onlyAsserted(OWLOntology ontology, OWLEntity entity) {
        boolean isClass = entity instanceof OWLClass;
        return ontology.referencingAxioms(entity).allMatch(axiom -> {
            if (axiom instanceof OWLObjectPropertyAssertionAxiom a) {
                return a.getProperty() instanceof OWLObjectProperty;
            }
            if (axiom instanceof OWLDeclarationAxiom || axiom instanceof OWLAnnotationAssertionAxiom
                    || axiom instanceof OWLDataPropertyAssertionAxiom) {
                return true;
            }
            if (axiom instanceof OWLClassAssertionAxiom a) {
                return a.getClassExpression() instanceof OWLClass;
            }
            if (axiom instanceof OWLObjectPropertyDomainAxiom || axiom instanceof OWLObjectPropertyRangeAxiom
                    || axiom instanceof OWLDataPropertyDomainAxiom || axiom instanceof OWLDataPropertyRangeAxiom) {
                return !isClass;
            }
            if (axiom instanceof OWLSubClassOfAxiom s) {
                return monotone(s.getSubClass()) && s.getSuperClass().signature().noneMatch(entity::equals);
            }
            if (axiom instanceof SWRLRule r) {
                return r.head().noneMatch(a -> a.signature().anyMatch(entity::equals))
                        && r.body().filter(SWRLClassAtom.class::isInstance).map(a -> ((SWRLClassAtom) a).getPredicate()).allMatch(RuleExecution::monotone);
            }
            return false;
        });
    }

    private static int absorb(Set<OWLAxiom> axioms, OWLOntology working, OWLOntologyManager manager,
                              OWLOntology engineBase, OWLOntologyManager baseManager,
                              Map<String, DerivedFact> derived, Origin origin, int round, long[] ignored,
                              Set<org.semanticweb.owlapi.model.OWLEntity> relevant) {
        int added = 0;
        List<OWLAxiom> ordered = axioms.stream().sorted(Comparator.comparing(Object::toString)).collect(Collectors.toList());
        for (OWLAxiom raw : ordered) {
            OWLAxiom axiom = raw.getAxiomWithoutAnnotations();
            if (axiom instanceof OWLClassAssertionAxiom a) {
                if (!(a.getClassExpression() instanceof OWLClass) || !a.getIndividual().isNamed()) {
                    ignored[0]++;
                    continue;
                }
            } else if (axiom instanceof OWLObjectPropertyAssertionAxiom a) {
                if (!(a.getProperty() instanceof OWLObjectProperty) || !a.getSubject().isNamed() || !a.getObject().isNamed()) {
                    ignored[0]++;
                    continue;
                }
            } else if (axiom instanceof OWLDataPropertyAssertionAxiom a) {
                if (!(a.getProperty() instanceof OWLDataProperty) || !a.getSubject().isNamed()) {
                    ignored[0]++;
                    continue;
                }
            } else {
                if (axiom.isOfType(AxiomType.SAME_INDIVIDUAL, AxiomType.DIFFERENT_INDIVIDUALS) && origin == Origin.RULE_ENGINE) {
                    ignored[1]++;
                }
                continue;
            }
            if (working.containsAxiom(axiom, Imports.EXCLUDED, AxiomAnnotations.IGNORE_AXIOM_ANNOTATIONS)) {
                continue;
            }
            manager.addAxiom(working, axiom);
            baseManager.addAxiom(engineBase, axiom);
            derived.putIfAbsent(canonical(axiom), new DerivedFact(axiom, origin, round));
            if (axiom.signature().anyMatch(relevant::contains)) {
                added++;
            }
        }
        return added;
    }

    private static void collect(SWRLAtom atom, Set<OWLClass> classes, Set<OWLObjectProperty> objectProperties, Set<OWLDataProperty> dataProperties) {
        atom.signature().forEach(entity -> {
            if (entity instanceof OWLClass c) {
                classes.add(c);
            } else if (entity instanceof OWLObjectProperty p) {
                objectProperties.add(p);
            } else if (entity instanceof OWLDataProperty d) {
                dataProperties.add(d);
            }
        });
    }

    private static boolean concludesEquality(SWRLRule rule) {
        return rule.head().anyMatch(atom -> atom instanceof SWRLSameIndividualAtom || atom instanceof SWRLDifferentIndividualsAtom);
    }

    private static boolean bodyUsesDifferentFrom(SWRLRule rule) {
        return rule.body().anyMatch(SWRLDifferentIndividualsAtom.class::isInstance);
    }

    private static boolean hasEqualityAxioms(OWLOntology ontology) {
        return ontology.axioms().anyMatch(axiom -> axiom.isOfType(AxiomType.SAME_INDIVIDUAL, AxiomType.DIFFERENT_INDIVIDUALS,
                AxiomType.FUNCTIONAL_OBJECT_PROPERTY, AxiomType.INVERSE_FUNCTIONAL_OBJECT_PROPERTY, AxiomType.HAS_KEY));
    }

    static String canonical(OWLAxiom axiom) {
        return axiom.getAxiomWithoutAnnotations().toString();
    }

    private static String labelOf(SWRLRule rule) {
        return rule.annotations()
                .filter(a -> a.getProperty().isLabel())
                .map(a -> a.getValue().asLiteral().map(l -> l.getLiteral()).orElse(null))
                .filter(v -> v != null && !v.isBlank())
                .findFirst().orElse(null);
    }

    public static String ruleName(SWRLRule rule) {
        String label = labelOf(rule);
        if (label != null) {
            return label;
        }
        return "Rule " + ruleId(rule).substring("rule-".length());
    }

    public static String ruleId(SWRLRule rule) {
        String label = labelOf(rule);
        if (label != null) {
            return label;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical(rule).getBytes(StandardCharsets.UTF_8));
            return "rule-" + HexFormat.of().formatHex(hash, 0, 4);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
