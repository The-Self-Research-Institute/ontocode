package self.research.ontology.common.rules;

import org.junit.jupiter.api.Test;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import self.research.ontology.common.RuleStage;
import self.research.ontology.common.rules.RuleExecutionResult.Limit;
import self.research.ontology.common.rules.RuleExecutionResult.Status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleExecutionTest {

    static final Path DIR = Path.of("..", "..", "test-data", "rule-capability");
    static final String HEAD = "Prefix(:=<http://example.org/rc#>)\nPrefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)\n"
            + "Prefix(swrlb:=<http://www.w3.org/2003/11/swrlb#>)\nPrefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\nOntology(<http://example.org/rc>\n";

    static OWLOntology fixture(String file) throws Exception {
        return load(Files.readString(DIR.resolve(file), StandardCharsets.UTF_8));
    }

    static OWLOntology inline(String body) throws Exception {
        return load(HEAD + body + ")\n");
    }

    static OWLOntology load(String text) throws Exception {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(text));
    }

    static RuleExecution execution(RuleStage stage) {
        return new RuleExecution(stage, ontology -> new ReasonerFactory().createReasoner(ontology), "HermiT 1.4.5.519");
    }

    static RuleExecutionResult run(OWLOntology ontology, RuleStage stage) {
        return execution(stage).run(ontology, "snap-1", RuleExecutionLimits.defaults());
    }

    static Set<String> derivedKeys(RuleExecutionResult result) {
        return result.derived().stream().map(d -> RuleExecution.canonical(d.axiom())).collect(Collectors.toCollection(TreeSet::new));
    }

    static boolean derives(RuleExecutionResult result, String fragment) {
        return derivedKeys(result).stream().anyMatch(k -> k.contains(fragment));
    }

    static boolean entails(OWLOntology working, String expectBody) throws Exception {
        OWLOntology expected = load(HEAD + expectBody + ")\n");
        OWLAxiom axiom = expected.getLogicalAxioms().iterator().next();
        OWLReasoner reasoner = new ReasonerFactory().createReasoner(working);
        try {
            return reasoner.isEntailed(axiom);
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void aChainOfRulesThatHermitCanRunNeverCallsTheRuleEngine() throws Exception {
        MiniRuleEngine engine = new MiniRuleEngine();
        RuleExecutionResult result = run(fixture("08-rule-chain.ofn"), engine);

        assertEquals(Status.COMPLETED, result.status());
        assertEquals(0, engine.calls.get());
        assertTrue(derives(result, "ClassAssertion(<http://example.org/rc#B>"), derivedKeys(result).toString());
        assertTrue(derives(result, "ClassAssertion(<http://example.org/rc#C>"), derivedKeys(result).toString());
        assertEquals(2, result.executedRules().size());
    }

    @Test
    void anOwlConclusionCanEnableARuleAndTheRuleResultFeedsOwlAgain() throws Exception {
        MiniRuleEngine engine = new MiniRuleEngine();
        RuleExecutionResult result = run(fixture("15-alternating-chain.ofn"), engine);

        assertEquals(Status.COMPLETED, result.status(), result.message());
        assertTrue(derives(result, "ClassAssertion(<http://example.org/rc#Adult>"), derivedKeys(result).toString());
        assertTrue(derives(result, "ClassAssertion(<http://example.org/rc#Staff>"), derivedKeys(result).toString());
        assertTrue(derives(result, "ClassAssertion(<http://example.org/rc#Flagged>"), derivedKeys(result).toString());
        assertTrue(result.stopping().converged());
        assertEquals(2, result.stopping().rounds());
        assertEquals(RuleExecutionResult.Origin.RULE_ENGINE, result.derived().stream()
                .filter(d -> d.axiom().toString().contains("#Flagged")).findFirst().get().origin());
        assertEquals(RuleExecutionResult.Origin.OWL_REASONER, result.derived().stream()
                .filter(d -> d.axiom().toString().contains("#Staff")).findFirst().get().origin());
    }

    @Test
    void aRuleResultThatEnablesOwlReasoningIsVisibleToTheFinalReasoner() throws Exception {
        RuleExecutionResult result = run(fixture("07-rule-feeds-owl.ofn"), new MiniRuleEngine());

        assertEquals(Status.COMPLETED, result.status());
        assertTrue(entails(result.working(), "ClassAssertion(:C :a)"));
    }

    @Test
    void aMathsRuleRunsInTheEngineAndItsConclusionIsReturned() throws Exception {
        MiniRuleEngine engine = new MiniRuleEngine();
        RuleExecutionResult result = run(fixture("04-maths-builtin.ofn"), engine);

        assertEquals(Status.COMPLETED, result.status());
        assertEquals(1, engine.calls.get());
        assertTrue(derives(result, "#Over18"));
    }

    @Test
    void twoNativeRulesThatFeedEachOtherReachAStableResultWithoutLooping() throws Exception {
        OWLOntology cycle = inline("Declaration(Class(:A))Declaration(Class(:B))Declaration(NamedIndividual(:a))\n"
                + "ClassAssertion(:A :a)\n"
                + "DLSafeRule(Body(ClassAtom(:A Variable(:x))) Head(ClassAtom(:B Variable(:x))))\n"
                + "DLSafeRule(Body(ClassAtom(:B Variable(:x))) Head(ClassAtom(:A Variable(:x))))\n");

        RuleExecutionResult result = run(cycle, new MiniRuleEngine());

        assertEquals(Status.COMPLETED, result.status());
        assertEquals(1, result.stopping().rounds());
        assertEquals(1, result.derived().size());
    }

    @Test
    void anEngineAndOwlCycleStopsOnceNothingNewAppears() throws Exception {
        OWLOntology cycle = inline("Declaration(Class(:A))Declaration(Class(:B))Declaration(DataProperty(:n))Declaration(NamedIndividual(:a))\n"
                + "ClassAssertion(:A :a)DataPropertyAssertion(:n :a \"1\"^^xsd:integer)\n"
                + "SubClassOf(:B :A)\n"
                + "DLSafeRule(Body(ClassAtom(:A Variable(:x)) DataPropertyAtom(:n Variable(:x) Variable(:v)) BuiltInAtom(swrlb:greaterThan Variable(:v) \"0\"^^xsd:integer)) Head(ClassAtom(:B Variable(:x))))\n");

        RuleExecutionResult result = run(cycle, new MiniRuleEngine());

        assertEquals(Status.COMPLETED, result.status());
        assertTrue(result.stopping().converged());
        assertTrue(result.stopping().rounds() <= 2, String.valueOf(result.stopping().rounds()));
    }

    @Test
    void theRoundLimitMarksTheResultPartial() throws Exception {
        RuleExecutionResult result = execution(new MiniRuleEngine())
                .run(fixture("15-alternating-chain.ofn"), "snap-1", new RuleExecutionLimits(1, 60_000, 1000, () -> false));

        assertEquals(Status.PARTIAL_LIMIT_REACHED, result.status());
        assertEquals(Limit.ROUNDS, result.stopping().limit());
        assertFalse(result.stopping().converged());
        assertTrue(result.message().contains("1 rounds"), result.message());
        assertFalse(result.consistencyStatement().contains("all enabled rules"));
    }

    @Test
    void theOutputLimitMarksTheResultPartial() throws Exception {
        RuleExecutionResult result = execution(new MiniRuleEngine())
                .run(fixture("15-alternating-chain.ofn"), "snap-1", new RuleExecutionLimits(8, 60_000, 1, () -> false));

        assertEquals(Status.PARTIAL_LIMIT_REACHED, result.status());
        assertEquals(Limit.DERIVED_FACTS, result.stopping().limit());
    }

    @Test
    void theTimeLimitMarksTheResultPartial() throws Exception {
        RuleExecutionResult result = execution(new MiniRuleEngine())
                .run(fixture("15-alternating-chain.ofn"), "snap-1", new RuleExecutionLimits(8, -1, 1000, () -> false));

        assertEquals(Status.PARTIAL_LIMIT_REACHED, result.status());
        assertEquals(Limit.TIME, result.stopping().limit());
    }

    @Test
    void cancellingStopsBeforeAnyConclusionIsDrawn() throws Exception {
        RuleExecutionResult result = execution(new MiniRuleEngine())
                .run(fixture("15-alternating-chain.ofn"), "snap-1", RuleExecutionLimits.defaults().withCancel(() -> true));

        assertEquals(Status.CANCELLED, result.status());
        assertTrue(result.derived().isEmpty());
        assertEquals("Consistency was not determined.", result.consistencyStatement());
    }

    @Test
    void cancellingBetweenTheEngineAndOwlStepKeepsWhatWasDerivedSoFarButMarksItCancelled() throws Exception {
        AtomicBoolean cancel = new AtomicBoolean();
        MiniRuleEngine engine = new MiniRuleEngine() {
            @Override
            public Optional<Set<OWLAxiom>> infer(OWLOntology ontology, java.util.Collection<org.semanticweb.owlapi.model.SWRLRule> rules) {
                Optional<Set<OWLAxiom>> out = super.infer(ontology, rules);
                cancel.set(true);
                return out;
            }
        };
        RuleExecutionResult result = execution(engine)
                .run(fixture("04-maths-builtin.ofn"), "snap-1", RuleExecutionLimits.defaults().withCancel(cancel::get));

        assertEquals(Status.CANCELLED, result.status());
        assertFalse(result.complete());
    }

    @Test
    void anUnavailableEngineSkipsItsRulesAndSaysTheirConclusionsAreUnknown() throws Exception {
        RuleExecutionResult result = run(fixture("04-maths-builtin.ofn"), MiniRuleEngine.down());

        assertEquals(Status.PARTIAL_ENGINE_UNAVAILABLE, result.status());
        assertEquals(1, result.skippedRules().size());
        assertTrue(result.skippedRules().get(0).reason().contains("could not be reached"));
        assertTrue(result.message().contains("unknown, not false"));
        assertFalse(derives(result, "#Over18"));
        assertFalse(result.consistencyStatement().contains("all enabled rules"), result.consistencyStatement());
        assertTrue(result.consistencyStatement().contains("incomplete"));
    }

    @Test
    void anUnavailableEngineStillLetsHermitRulesRun() throws Exception {
        OWLOntology both = inline("Declaration(Class(:A))Declaration(Class(:B))Declaration(Class(:Over18))Declaration(DataProperty(:hasAge))Declaration(NamedIndividual(:a))\n"
                + "ClassAssertion(:A :a)DataPropertyAssertion(:hasAge :a \"30\"^^xsd:integer)\n"
                + "DLSafeRule(Body(ClassAtom(:A Variable(:x))) Head(ClassAtom(:B Variable(:x))))\n"
                + "DLSafeRule(Body(DataPropertyAtom(:hasAge Variable(:x) Variable(:v)) BuiltInAtom(swrlb:add Variable(:w) Variable(:v) \"1\"^^xsd:integer) BuiltInAtom(swrlb:greaterThan Variable(:w) \"18\"^^xsd:integer)) Head(ClassAtom(:Over18 Variable(:x))))\n");

        RuleExecutionResult result = run(both, MiniRuleEngine.down());

        assertEquals(Status.PARTIAL_ENGINE_UNAVAILABLE, result.status());
        assertTrue(derives(result, "#B>"));
        assertFalse(derives(result, "#Over18"));
        assertEquals(1, result.executedRules().size());
        assertEquals(1, result.skippedRules().size());
    }

    @Test
    void aNullEngineIsTreatedAsNotAvailable() throws Exception {
        RuleExecutionResult result = run(fixture("04-maths-builtin.ofn"), null);

        assertEquals(Status.PARTIAL_RULES_SKIPPED, result.status());
        assertEquals(1, result.skippedRules().size());
    }

    @Test
    void anEngineThatThrowsIsTreatedAsUnavailable() throws Exception {
        RuleStage broken = (ontology, rules) -> {
            throw new IllegalStateException("boom");
        };
        RuleExecutionResult result = run(fixture("04-maths-builtin.ofn"), broken);

        assertEquals(Status.PARTIAL_ENGINE_UNAVAILABLE, result.status());
    }

    @Test
    void retryAfterTheEngineComesBackGivesTheFullResult() throws Exception {
        MiniRuleEngine flaky = new MiniRuleEngine(50, call -> call > 1);
        RuleExecution execution = execution(flaky);
        OWLOntology ontology = fixture("04-maths-builtin.ofn");

        RuleExecutionResult first = execution.run(ontology, "snap-1", RuleExecutionLimits.defaults());
        RuleExecutionResult second = execution.run(ontology, "snap-1", RuleExecutionLimits.defaults());

        assertEquals(Status.PARTIAL_ENGINE_UNAVAILABLE, first.status());
        assertEquals(Status.COMPLETED, second.status());
        assertTrue(derives(second, "#Over18"));
        assertEquals(first.snapshotId(), second.snapshotId());
    }

    @Test
    void anInconsistentOntologyIsReportedAsInconsistentWithTheScopeStated() throws Exception {
        OWLOntology bad = inline("Declaration(Class(:A))Declaration(Class(:B))Declaration(NamedIndividual(:a))\n"
                + "ClassAssertion(:A :a)ClassAssertion(:B :a)DisjointClasses(:A :B)\n"
                + "DLSafeRule(Body(ClassAtom(:A Variable(:x))) Head(ClassAtom(:B Variable(:x))))\n");

        RuleExecutionResult result = run(bad, new MiniRuleEngine());

        assertEquals(Status.INCONSISTENT, result.status());
        assertTrue(result.consistencyStatement().contains("as far as the OWL axioms and the rules that ran can tell"));
    }

    @Test
    void anOwlOnlyConsistencyCheckIsNeverReportedAsAllRulesConsistent() throws Exception {
        RuleExecutionResult result = run(fixture("04-maths-builtin.ofn"), MiniRuleEngine.down());

        assertNotEquals("The ontology and all enabled rules are consistent within the supported rule profile.", result.consistencyStatement());
    }

    @Test
    void aFactWithTwoIndependentJustificationsIsListedOnceAndDisappearsOnlyWhenBothRulesGo() throws Exception {
        String base = "Declaration(Class(:A))Declaration(Class(:A2))Declaration(Class(:B))Declaration(NamedIndividual(:a))\n"
                + "ClassAssertion(:A :a)ClassAssertion(:A2 :a)\n";
        String r1 = "DLSafeRule(Body(ClassAtom(:A Variable(:x))) Head(ClassAtom(:B Variable(:x))))\n";
        String r2 = "DLSafeRule(Body(ClassAtom(:A2 Variable(:x))) Head(ClassAtom(:B Variable(:x))))\n";

        assertEquals(1, run(inline(base + r1 + r2), new MiniRuleEngine()).derived().size());
        assertTrue(derives(run(inline(base + r2), new MiniRuleEngine()), "#B>"));
        assertTrue(derives(run(inline(base + r1), new MiniRuleEngine()), "#B>"));
        assertTrue(run(inline(base), new MiniRuleEngine()).derived().isEmpty());
    }

    @Test
    void removingARuleRemovesItsConclusionsBecauseEveryRunStartsFromTheAssertedFacts() throws Exception {
        RuleExecution execution = execution(new MiniRuleEngine());
        RuleExecutionResult withRule = execution.run(fixture("04-maths-builtin.ofn"), "s1", RuleExecutionLimits.defaults());
        OWLOntology without = fixture("04-maths-builtin.ofn");
        without.getOWLOntologyManager().removeAxioms(without, without.axioms(org.semanticweb.owlapi.model.AxiomType.SWRL_RULE).collect(Collectors.toList()));
        RuleExecutionResult withoutRule = execution.run(without, "s2", RuleExecutionLimits.defaults());

        assertTrue(derives(withRule, "#Over18"));
        assertFalse(derives(withoutRule, "#Over18"));
        assertFalse(without.axioms().anyMatch(a -> a instanceof org.semanticweb.owlapi.model.OWLClassAssertionAxiom && a.toString().contains("Over18")));
    }

    @Test
    void derivedFactsNeverChangeTheAssertedOntology() throws Exception {
        OWLOntology ontology = fixture("15-alternating-chain.ofn");
        Set<OWLAxiom> before = ontology.axioms().collect(Collectors.toSet());

        RuleExecutionResult result = run(ontology, new MiniRuleEngine());
        ontology.getOWLOntologyManager().addAxiom(ontology, ontology.getOWLOntologyManager().getOWLDataFactory()
                .getOWLClassAssertionAxiom(ontology.getOWLOntologyManager().getOWLDataFactory().getOWLClass("http://example.org/rc#Visitor"),
                        ontology.getOWLOntologyManager().getOWLDataFactory().getOWLNamedIndividual("http://example.org/rc#a")));

        assertFalse(result.derived().isEmpty());
        assertTrue(before.stream().allMatch(ontology::containsAxiom));
        assertFalse(ontology.axioms().anyMatch(a -> a instanceof org.semanticweb.owlapi.model.OWLClassAssertionAxiom && a.toString().contains("Flagged>")));
        assertFalse(result.working().axioms().anyMatch(a -> a.toString().contains("Visitor> <http://example.org/rc#a>)")),
                "a later edit must not leak into a finished result");
    }

    @Test
    void equivalentRulesInADifferentOrderWithDifferentNamesGiveTheSameFacts() throws Exception {
        String base = "Declaration(Class(:A))Declaration(Class(:B))Declaration(Class(:C))Declaration(NamedIndividual(:a))ClassAssertion(:A :a)\n";
        String ab = "DLSafeRule(Annotation(rdfs:label \"first\") Body(ClassAtom(:A Variable(:x))) Head(ClassAtom(:B Variable(:x))))\n";
        String bc = "DLSafeRule(Annotation(rdfs:label \"second\") Body(ClassAtom(:B Variable(:x))) Head(ClassAtom(:C Variable(:x))))\n";
        String abRenamed = "DLSafeRule(Annotation(rdfs:label \"zeta\") Body(ClassAtom(:A Variable(:y))) Head(ClassAtom(:B Variable(:y))))\n";
        String bcRenamed = "DLSafeRule(Annotation(rdfs:label \"alpha\") Body(ClassAtom(:B Variable(:y))) Head(ClassAtom(:C Variable(:y))))\n";

        RuleExecutionResult one = run(inline(base + ab + bc), new MiniRuleEngine());
        RuleExecutionResult two = run(inline(base + bcRenamed + abRenamed), new MiniRuleEngine());

        assertEquals(derivedKeys(one), derivedKeys(two));
        assertEquals(Status.COMPLETED, two.status());
    }

    @Test
    void aBuiltInRuleThatConcludesSameAsIsSkippedAsUnsupported() throws Exception {
        RuleExecutionResult result = run(fixture("16-builtin-sameas-head.ofn"), new MiniRuleEngine());

        assertEquals(Status.PARTIAL_RULES_SKIPPED, result.status());
        assertEquals(1, result.skippedRules().size());
        assertTrue(result.skippedRules().get(0).reason().contains("sameAs"));
    }

    @Test
    void aRuleOverUnnamedIndividualsIsReportedAsALimitation() throws Exception {
        RuleExecutionResult result = run(fixture("11-anonymous-individual.ofn"), new MiniRuleEngine());

        assertEquals(Status.COMPLETED, result.status());
        assertTrue(result.limitations().stream().anyMatch(l -> l.contains("unnamed")), result.limitations().toString());
    }

    @Test
    void aRuleThatTestsDifferentFromIsRoutedToTheEngineNotHermit() throws Exception {
        MiniRuleEngine engine = new MiniRuleEngine();
        RuleExecutionResult result = run(fixture("10-differentfrom-body.ofn"), engine);

        assertEquals(1, engine.calls.get());
        assertTrue(result.status() == Status.COMPLETED || result.status() == Status.PARTIAL_ENGINE_UNAVAILABLE);
    }

    @Test
    void conclusionsTheEngineReturnsAboutUnnamedOrNonFactAxiomsAreNotTransferred() throws Exception {
        org.semanticweb.owlapi.model.OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLAxiom subclass = df.getOWLSubClassOfAxiom(df.getOWLClass("http://example.org/rc#Over18"), df.getOWLClass("http://example.org/rc#Over17"));
        OWLAxiom anonymous = df.getOWLClassAssertionAxiom(df.getOWLClass("http://example.org/rc#Over18"), df.getOWLAnonymousIndividual());
        OWLAxiom same = df.getOWLSameIndividualAxiom(df.getOWLNamedIndividual("http://example.org/rc#a"), df.getOWLNamedIndividual("http://example.org/rc#z"));
        RuleStage noisy = (ontology, rules) -> Optional.of(Set.of(subclass, anonymous, same));

        RuleExecutionResult result = run(fixture("04-maths-builtin.ofn"), noisy);

        assertTrue(result.derived().isEmpty());
        assertTrue(result.limitations().stream().anyMatch(l -> l.contains("unnamed")));
        assertTrue(result.limitations().stream().anyMatch(l -> l.contains("sameAs")));
    }

    @Test
    void theResultNamesTheSnapshotAndTheEngineVersions() throws Exception {
        RuleExecutionResult result = run(fixture("04-maths-builtin.ofn"), new MiniRuleEngine());

        assertEquals("snap-1", result.snapshotId());
        assertTrue(result.engineVersions().get("owlReasoner").startsWith("HermiT"));
        assertEquals("mini test engine", result.engineVersions().get("ruleEngine"));
    }

    @Test
    void separateRunsOnDifferentProjectsAtTheSameTimeDoNotInterfere() throws Exception {
        MiniRuleEngine engine = new MiniRuleEngine();
        RuleExecution execution = execution(engine);
        OWLOntology one = fixture("04-maths-builtin.ofn");
        OWLOntology two = fixture("08-rule-chain.ofn");
        List<RuleExecutionResult> results = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread a = new Thread(() -> results.add(execution.run(one, "A", RuleExecutionLimits.defaults())));
        Thread b = new Thread(() -> results.add(execution.run(two, "B", RuleExecutionLimits.defaults())));
        a.start();
        b.start();
        a.join();
        b.join();

        RuleExecutionResult first = results.stream().filter(r -> r.snapshotId().equals("A")).findFirst().get();
        RuleExecutionResult second = results.stream().filter(r -> r.snapshotId().equals("B")).findFirst().get();
        assertTrue(derives(first, "#Over18"));
        assertFalse(derives(first, "#C>"));
        assertTrue(derives(second, "#C>"));
        assertFalse(derives(second, "#Over18"));
    }

    @Test
    void anOntologyWithoutRulesIsCompleteAndHasNoDerivedFacts() throws Exception {
        RuleExecutionResult result = run(inline("Declaration(Class(:A))"), new MiniRuleEngine());

        assertEquals(Status.COMPLETED, result.status());
        assertTrue(result.derived().isEmpty());
    }

    @Test
    void theEngineOnlySeesAssertedFactsAndItsOwnRulesNotTheOwlTranslationOrHermitRules() throws Exception {
        OWLOntology mixed = inline("Declaration(Class(:Adult))Declaration(Class(:Over18))Declaration(Class(:A))Declaration(Class(:B))"
                + "Declaration(DataProperty(:hasAge))Declaration(NamedIndividual(:a))\n"
                + "ClassAssertion(:A :a)DataPropertyAssertion(:hasAge :a \"30\"^^xsd:integer)\n"
                + "DLSafeRule(Body(DataPropertyAtom(:hasAge Variable(:x) Variable(:v)) BuiltInAtom(swrlb:greaterThan Variable(:v) \"17\"^^xsd:integer)) Head(ClassAtom(:Adult Variable(:x))))\n"
                + "DLSafeRule(Body(ClassAtom(:A Variable(:x))) Head(ClassAtom(:B Variable(:x))))\n"
                + "DLSafeRule(Body(DataPropertyAtom(:hasAge Variable(:x) Variable(:v)) BuiltInAtom(swrlb:add Variable(:w) Variable(:v) \"1\"^^xsd:integer) "
                + "BuiltInAtom(swrlb:greaterThan Variable(:w) \"18\"^^xsd:integer)) Head(ClassAtom(:Over18 Variable(:x))))\n");
        List<long[]> seen = new java.util.ArrayList<>();
        RuleStage spy = (ontology, rules) -> {
            seen.add(new long[]{
                    ontology.axioms(org.semanticweb.owlapi.model.AxiomType.SWRL_RULE).count(),
                    ontology.axioms().filter(a -> a instanceof org.semanticweb.owlapi.model.OWLSubClassOfAxiom
                            || a instanceof org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom).count()});
            return Optional.of(Set.of());
        };

        run(mixed, spy);

        assertEquals(1, seen.size());
        assertEquals(1, seen.get(0)[0], "only the engine's own rule is sent");
        assertEquals(0, seen.get(0)[1], "no OWL translation should reach the engine");
    }
}
