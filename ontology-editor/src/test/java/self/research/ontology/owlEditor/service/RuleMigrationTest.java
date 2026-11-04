package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLRule;
import self.research.ontology.common.rules.DisabledRules;
import self.research.ontology.owlEditor.util.OwlAxiomSparqlWriter;
import self.research.ontology.owlEditor.util.OwlAxiomSparqlWriterRuleTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleMigrationTest {

    static final String MAIN = "urn:graph:main";
    static final String BASE = "urn:graph:base";
    static final String DRAFT = "urn:graph:draft:u1";

    final ObjectMapper mapper = new ObjectMapper();
    final List<SWRLRule> demo = new OwlAxiomSparqlWriterRuleTest().demoRules();
    final List<Map<String, Object>> legacy = Collections.synchronizedList(new ArrayList<>());
    final List<String> marked = Collections.synchronizedList(new ArrayList<>());
    final List<ChangeOrigin> origins = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger legacyReads = new AtomicInteger();
    final AtomicBoolean failMarking = new AtomicBoolean();
    final List<String> readyUsers = new ArrayList<>();
    RuleTestSupport.Store store;
    RuleService ruleService;
    RuleMigrationService migration;

    @BeforeEach
    void setUp() throws Exception {
        store = new RuleTestSupport.Store();
        store.put(MAIN, RuleTestSupport.parse(RuleTestSupport.seedTurtle(), RDFFormat.TURTLE));
        store.copy(MAIN, BASE);
        store.copy(MAIN, DRAFT);
        SwrlRuleClient client = new SwrlRuleClient((method, url, body, token) -> {
            if (url.endsWith("/rules/legacy")) {
                legacyReads.incrementAndGet();
                List<Map<String, Object>> open = new ArrayList<>();
                synchronized (legacy) {
                    for (Map<String, Object> row : legacy) {
                        if (!marked.contains(String.valueOf(row.get("id")))) {
                            open.add(row);
                        }
                    }
                }
                Thread.sleep(30);
                return new SwrlRuleClient.Answer(200, mapper.writeValueAsString(open));
            }
            if (url.endsWith("/migrated")) {
                if (failMarking.get()) {
                    return new SwrlRuleClient.Answer(500, "{}");
                }
                String[] parts = url.split("/");
                marked.add(parts[parts.length - 2]);
                return new SwrlRuleClient.Answer(200, "{}");
            }
            if (url.endsWith("/rules/parse")) {
                JsonNode request = mapper.readTree(body);
                String text = request.path("ruleText").asText();
                Map<String, Object> answer = new HashMap<>();
                if (text.startsWith("demo-")) {
                    SWRLRule rule = demo.get(Integer.parseInt(text.substring(5)));
                    answer.put("valid", true);
                    answer.put("axiom", RuleTestSupport.functional(rule.getAxiomWithoutAnnotations()));
                    answer.put("errors", List.of());
                    answer.put("suggestions", List.of());
                } else {
                    answer.put("valid", false);
                    answer.put("axiom", null);
                    answer.put("errors", List.of("I do not understand '" + text + "'"));
                    answer.put("suggestions", List.of());
                }
                return new SwrlRuleClient.Answer(200, mapper.writeValueAsString(answer));
            }
            return new SwrlRuleClient.Answer(200, "[]");
        }, "http://swrl", "t");
        ruleService = new RuleService(client,
                (projectId, draft, user) -> store.ontology(draft ? DRAFT : MAIN),
                (projectId, sparql, draft, user, name, origin) -> {
                    origins.add(origin);
                    store.update(draft ? DRAFT : MAIN, sparql);
                });
        RuleMigrationService.DraftTargets targets = new RuleMigrationService.DraftTargets() {
            @Override
            public List<String> readyDraftUsers(String projectId) {
                return readyUsers;
            }

            @Override
            public void addToDraft(String projectId, String userId, Set<SWRLRule> axioms) throws Exception {
                store.update(DRAFT, OwlAxiomSparqlWriter.toInsertData(axioms));
            }

            @Override
            public void addToBaseline(String projectId, String userId, Set<SWRLRule> axioms) throws Exception {
                store.update(BASE, OwlAxiomSparqlWriter.toInsertData(axioms));
            }
        };
        migration = new RuleMigrationService(client, ruleService, new ProjectWriteLockRegistry(), targets);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    void row(String id, String name, String text, String comment, String category, boolean enabled) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("ruleName", name);
        row.put("ruleText", text);
        row.put("comment", comment);
        row.put("category", category);
        row.put("enabled", enabled);
        legacy.add(row);
    }

    void twelveDemoRows() {
        for (int i = 0; i < 12; i++) {
            row("m" + i, "Demo rule " + i, "demo-" + i, "comment " + i, i % 2 == 0 ? "Even" : null, i != 3);
        }
    }

    Set<SWRLRule> rules(String graph) {
        return RuleTestSupport.rulesOf(store.ontology(graph));
    }

    Map<String, SWRLRule> byName(String graph) {
        Map<String, SWRLRule> map = new LinkedHashMap<>();
        for (SWRLRule rule : rules(graph)) {
            map.put(SwrlRuleKeys.name(rule).orElse("-"), rule);
        }
        return map;
    }

    void addPlainExistingRuleNamed(String name) throws Exception {
        RuleService.Built built = ruleService.buildNew("p", new RuleService.RuleInput(name, "demo-0", null, null, null));
        store.update(MAIN, OwlAxiomSparqlWriter.toInsertData(Set.of(built.axiom())));
        store.copy(MAIN, BASE);
        store.clear(DRAFT);
        store.copy(MAIN, DRAFT);
    }

    @Test
    void theTwelveDemoRulesMoveIntoThePublicGraphWithTheirDetailsAndEachIsMarked() throws Exception {
        twelveDemoRows();

        RuleMigrationService.Report report = migration.migrate("p");

        assertEquals(12, report.migrated());
        assertTrue(report.duplicates().isEmpty());
        assertTrue(report.unmigrated().isEmpty());
        assertEquals(12, rules(MAIN).size());
        assertEquals(12, new HashSet<>(marked).size());
        Map<String, SWRLRule> saved = byName(MAIN);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            SWRLRule rule = saved.get("Demo rule " + i);
            assertEquals(demo.get(i).getAxiomWithoutAnnotations(), rule.getAxiomWithoutAnnotations(), "rule " + i);
            assertTrue(ids.add(SwrlRuleKeys.ruleId(rule).orElseThrow()));
            assertEquals(i != 3, !DisabledRules.isDisabled(rule));
            final int index = i;
            assertTrue(rule.annotationsAsList().stream().anyMatch(a -> a.getProperty().isComment()
                    && a.getValue().asLiteral().orElseThrow().getLiteral().equals("comment " + index)));
        }
        assertTrue(DisabledRules.isDisabled(saved.get("Demo rule 3")));
        assertTrue(saved.get("Demo rule 2").annotationsAsList().stream()
                .anyMatch(a -> DisabledRules.CATEGORY.equals(a.getProperty().getIRI())
                        && a.getValue().toString().contains("Even")));
    }

    @Test
    void eachRowIsOneChangeSetWithAToolOriginAndTheMigrationLabel() throws Exception {
        twelveDemoRows();

        migration.migrate("p");

        assertEquals(12, origins.size());
        assertEquals(12, origins.stream().map(ChangeOrigin::changeSetId).distinct().count());
        for (ChangeOrigin origin : origins) {
            assertEquals(ChangeOrigin.TOOL, origin.source());
            assertEquals("Migrated saved rule", origin.toolLabel());
        }
    }

    @Test
    void aRowWhoseNameAlreadyExistsIsMarkedAndReportedAndNotWrittenTwice() throws Exception {
        addPlainExistingRuleNamed("Shared name");
        row("d1", "shared NAME", "demo-1", null, null, true);
        row("m1", "Another", "demo-2", null, null, true);

        RuleMigrationService.Report report = migration.migrate("p");

        assertEquals(1, report.migrated());
        assertEquals(List.of("shared NAME"), report.duplicates());
        assertTrue(marked.contains("d1"));
        assertEquals(2, rules(MAIN).size());
    }

    @Test
    void aRowThatNoLongerParsesIsReturnedAndLeftUnmarkedWhileTheOthersMove() throws Exception {
        row("m1", "Good", "demo-1", null, null, true);
        row("bad1", "Old rule", "Person(?x) ^^ nonsense", "was fine once", null, true);

        RuleMigrationService.Report report = migration.migrate("p");

        assertEquals(1, report.migrated());
        assertEquals(1, report.unmigrated().size());
        RuleMigrationService.Unmigrated left = report.unmigrated().get(0);
        assertEquals("bad1", left.id());
        assertEquals("Old rule", left.ruleName());
        assertEquals("Person(?x) ^^ nonsense", left.ruleText());
        assertTrue(left.error().contains("do not understand"));
        assertFalse(marked.contains("bad1"));
        assertEquals(1, rules(MAIN).size());
        assertEquals(1, migration.ensureMigrated("p").size());
    }

    @Test
    void runningTheMigrationAgainChangesNothing() throws Exception {
        twelveDemoRows();
        migration.migrate("p");
        Set<SWRLRule> after = rules(MAIN);

        RuleMigrationService.Report second = migration.migrate("p");

        assertEquals(0, second.migrated());
        assertEquals(after, rules(MAIN));
    }

    @Test
    void ifMarkingFailsHalfWayTheRetryDoesNotDuplicateWhatWasAlreadyWritten() throws Exception {
        twelveDemoRows();
        failMarking.set(true);
        assertThrows(SwrlRuleClient.UnavailableException.class, () -> migration.migrate("p"));
        assertEquals(1, rules(MAIN).size());
        failMarking.set(false);

        RuleMigrationService.Report retry = migration.migrate("p");

        assertEquals(11, retry.migrated());
        assertEquals(1, retry.duplicates().size());
        assertEquals(12, rules(MAIN).size());
        assertEquals(12, new HashSet<>(marked).size());
    }

    @Test
    void twoSimultaneousFirstCallsWriteEveryRuleExactlyOnce() throws Exception {
        twelveDemoRows();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            calls.add(pool.submit(() -> {
                start.await();
                return migration.ensureMigrated("p");
            }));
        }
        start.countDown();
        for (Future<?> call : calls) {
            call.get();
        }
        pool.shutdown();

        assertEquals(12, rules(MAIN).size());
        assertEquals(12, byName(MAIN).size());
        assertEquals(1, legacyReads.get());
    }

    @Test
    void aLateLegacyRowWithAnExistingNameIsTreatedAsADuplicate() throws Exception {
        twelveDemoRows();
        migration.migrate("p");

        row("late", "Demo rule 5", "demo-5", null, null, true);
        RuleMigrationService.Report again = migration.migrate("p");

        assertEquals(0, again.migrated());
        assertEquals(List.of("Demo rule 5"), again.duplicates());
        assertEquals(12, rules(MAIN).size());
    }

    @Test
    void aDraftOpenedBeforeTheMigrationSeesTheMigratedRulesAndDeletingOneThereIsPublished() throws Exception {
        twelveDemoRows();
        readyUsers.add("u1");
        assertTrue(rules(DRAFT).isEmpty());

        migration.migrate("p");

        assertEquals(12, rules(DRAFT).size());
        assertEquals(12, rules(BASE).size());
        String id = SwrlRuleKeys.ruleId(byName(DRAFT).get("Demo rule 7")).orElseThrow();
        ruleService.delete("p", id, true, "u1", "U", ChangeOrigin.manual());

        OWLOntology merged = publish();
        assertEquals(11, RuleTestSupport.rulesOf(merged).size());
        assertFalse(RuleTestSupport.rulesOf(merged).stream().anyMatch(r -> "Demo rule 7".equals(SwrlRuleKeys.name(r).orElse(""))));
    }

    @Test
    void aDraftThatWasNotTouchedKeepsWorkingAndPublishingKeepsTheMigratedRulesWithoutDuplicates() throws Exception {
        twelveDemoRows();
        migration.migrate("p");
        assertTrue(rules(DRAFT).isEmpty());

        OWLOntology merged = publish();

        assertEquals(12, RuleTestSupport.rulesOf(merged).size());
    }

    @Test
    void aDraftThatAlreadyHoldsTheSameRulesAsPublicPublishesWithoutDuplicatesEvenIfTheBaselineWasNotUpdated() throws Exception {
        twelveDemoRows();
        migration.migrate("p");
        store.clear(DRAFT);
        store.copy(MAIN, DRAFT);

        OWLOntology merged = publish();

        assertEquals(12, RuleTestSupport.rulesOf(merged).size());
    }

    @Test
    void aDraftRuleWithTheSameNameBlocksCopyingAndTheDraftKeepsItsOwnRule() throws Exception {
        row("m1", "Clash", "demo-1", null, null, true);
        readyUsers.add("u1");
        RuleService.Built mine = ruleService.buildNew("p", new RuleService.RuleInput("Clash", "demo-2", null, null, null));
        store.update(DRAFT, OwlAxiomSparqlWriter.toInsertData(Set.of(mine.axiom())));

        migration.migrate("p");

        assertEquals(1, rules(DRAFT).size());
        assertEquals(demo.get(2).getAxiomWithoutAnnotations(), rules(DRAFT).iterator().next().getAxiomWithoutAnnotations());
        assertEquals(1, rules(MAIN).size());
    }

    OWLOntology publish() throws Exception {
        OntologyMergeService merge = new OntologyMergeService(null, null, null, null, null);
        OWLOntology base = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(store.read(BASE)));
        OWLOntology draft = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(store.read(DRAFT)));
        OWLOntology main = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(store.read(MAIN)));
        Set<String> conflicts = new LinkedHashSet<>(merge.collectTouchedIris(base, main));
        conflicts.retainAll(merge.collectTouchedIris(base, draft));
        return merge.mergeDraftPublishThreeWay(base, draft, main, conflicts, new HashMap<>(), merge.header(base));
    }
}
