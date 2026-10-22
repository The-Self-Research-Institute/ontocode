package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.formats.NTriplesDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.InconsistentOntologyException;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import self.research.ontology.common.ReasoningFriendlyErrors;
import self.research.ontology.common.ReasoningSnapshot;
import self.research.ontology.reasoner.model.ReasoningJob;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class LegacyJobsFromSnapshotTest {

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void legacyJobsOnOneRevisionShareASingleReasoningSession() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor()
                .projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        ReasoningJobExecutor executor = executor(sessions);

        for (ReasoningJob.JobType type : List.of(ReasoningJob.JobType.REASONER_HIERARCHY,
                ReasoningJob.JobType.REASONER_OBJ_PROP_HIERARCHY, ReasoningJob.JobType.REASONER_DATA_PROP_HIERARCHY,
                ReasoningJob.JobType.REASONER_CLASSIFY, ReasoningJob.JobType.REASONER_INFERRED_AXIOMS,
                ReasoningJob.JobType.REASONER_SNAPSHOT, ReasoningJob.JobType.REASONER_CONSISTENCY)) {
            assertEquals(true, executor.execute(job(type, "HERMIT", null)).get("success"), type.name());
        }

        verify(sessions, times(1)).openSnapshotSession(any(), any(), any());
        verify(sessions, never()).openSession(any(), any(), any());
        assertEquals(1, editor.loads.get());

        editor.bumpRevision("P1");
        executor.execute(job(ReasoningJob.JobType.REASONER_HIERARCHY, "HERMIT", null));
        executor.execute(job(ReasoningJob.JobType.REASONER_CLASSIFY, "HERMIT", null));
        verify(sessions, times(2)).openSnapshotSession(any(), any(), any());
    }

    @Test
    void jobsAtRevisionZeroEachBuildTheirOwnSnapshot() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor()
                .projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA)
                .revision("P1", 0);
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        ReasoningJobExecutor executor = executor(sessions);

        Map<String, Object> first = executor.execute(job(ReasoningJob.JobType.REASONER_HIERARCHY, "OPENLLET", null));
        Map<String, Object> second = executor.execute(job(ReasoningJob.JobType.REASONER_HIERARCHY, "OPENLLET", null));
        Map<String, Object> consistency = executor.execute(
                job(ReasoningJob.JobType.REASONER_CONSISTENCY, "OPENLLET", null));

        verify(sessions, times(2)).openSnapshotSession(any(), any(), any());
        verify(sessions, times(1)).openSession(any(), any(), any());
        assertEquals(3, editor.loads.get());
        assertEquals(first.get("totalClasses"), second.get("totalClasses"));
        assertEquals(0L, first.get("ontologyRevision"));
        assertEquals(true, consistency.get("consistent"));
    }

    @Test
    void sameRevisionWithADifferentTripleCountGivesTheNewAnswer() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor()
                .projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        ReasoningJobExecutor executor = executor(sessions);

        Map<String, Object> before = executor.execute(job(ReasoningJob.JobType.REASONER_CLASSIFY, "OPENLLET", null));
        editor.project("P1", SnapshotTestSupport.CONSISTENT);
        Map<String, Object> after = executor.execute(job(ReasoningJob.JobType.REASONER_CLASSIFY, "OPENLLET", null));

        verify(sessions, times(2)).openSnapshotSession(any(), any(), any());
        assertEquals(2, editor.loads.get());
        assertTrue(!before.get("totalClasses").equals(after.get("totalClasses")));
        assertEquals(1L, after.get("ontologyRevision"));
    }

    @Test
    void ownerJobsAreBuiltForThatOwnerAndNeverShared() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor()
                .projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA)
                .draft("alice@example.com", SnapshotTestSupport.INCONSISTENT);
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        ReasoningJobExecutor executor = executor(sessions);

        executor.execute(job(ReasoningJob.JobType.REASONER_HIERARCHY, "OPENLLET", null));
        Map<String, Object> draftClassify = executor.execute(
                job(ReasoningJob.JobType.REASONER_CLASSIFY, "OPENLLET", "alice@example.com"));
        Map<String, Object> draftAgain = executor.execute(
                job(ReasoningJob.JobType.REASONER_CLASSIFY, "OPENLLET", "alice@example.com"));
        Map<String, Object> publishedClassify = executor.execute(
                job(ReasoningJob.JobType.REASONER_CLASSIFY, "OPENLLET", null));

        verify(sessions, times(3)).openSnapshotSession(any(), any(), any());
        assertEquals(true, draftClassify.get("inconsistent"));
        assertEquals(true, draftAgain.get("inconsistent"));
        assertFalse(draftClassify.containsKey("ontologyRevision"));
        assertEquals("Classification completed successfully", publishedClassify.get("message"));
        assertEquals(1L, publishedClassify.get("ontologyRevision"));

        Map<String, Object> draftConsistency = executor.execute(
                job(ReasoningJob.JobType.REASONER_CONSISTENCY, "OPENLLET", "alice@example.com"));
        assertEquals(false, draftConsistency.get("consistent"));
        verify(sessions, times(1)).openSession(any(), any(), any());
    }

    @Test
    void legacyResultsKeepTheKeysTheyHadBefore() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor()
                .projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        ReasoningJobExecutor executor = executor(SnapshotTestSupport.sessions(editor));

        Map<String, Object> hierarchy = executor.execute(job(ReasoningJob.JobType.REASONER_HIERARCHY, "HERMIT", null));
        assertEquals(Set.of("success", "hierarchy", "reasonerType", "totalClasses", "ontologyRevision"),
                hierarchy.keySet());
        assertEquals("HermiT", hierarchy.get("reasonerType"));

        Map<String, Object> objectProperties = executor.execute(
                job(ReasoningJob.JobType.REASONER_OBJ_PROP_HIERARCHY, "HERMIT", null));
        assertEquals(Set.of("success", "hierarchy", "reasonerType", "ontologyRevision"), objectProperties.keySet());
        assertEquals(1, ((List<?>) objectProperties.get("hierarchy")).size());

        Map<String, Object> dataProperties = executor.execute(
                job(ReasoningJob.JobType.REASONER_DATA_PROP_HIERARCHY, "HERMIT", null));
        assertEquals(Set.of("success", "hierarchy", "reasonerType", "ontologyRevision"), dataProperties.keySet());

        Map<String, Object> classify = executor.execute(job(ReasoningJob.JobType.REASONER_CLASSIFY, "HERMIT", null));
        assertEquals(Set.of("success", "reasonerType", "durationMs", "message", "classHierarchy", "equivalentClasses",
                "unsatisfiableClasses", "objectPropertyHierarchy", "dataPropertyHierarchy", "totalClasses", "stats",
                "ontologyRevision"), classify.keySet());
        assertEquals(Set.of("classHierarchyNodes", "objectPropertyNodes", "dataPropertyNodes", "individuals",
                "satisfiableClasses", "unsatisfiableClasses", "isConsistent"),
                ((Map<?, ?>) classify.get("stats")).keySet());
        assertFalse(((List<?>) classify.get("classHierarchy")).isEmpty());
        assertEquals(Set.of("iri", "label", "depth", "childrenCount"),
                ((Map<?, ?>) ((List<?>) classify.get("classHierarchy")).get(0)).keySet());

        Map<String, Object> inferred = executor.execute(
                job(ReasoningJob.JobType.REASONER_INFERRED_AXIOMS, "HERMIT", null));
        Set<String> inferredKeys = new HashSet<>(inferred.keySet());
        inferredKeys.remove("totalIsPartial");
        assertEquals(Set.of("success", "reasonerType", "durationMs", "totalInferredAxioms", "axioms", "message",
                "ontologyRevision"), inferredKeys);

        Map<String, Object> consistency = executor.execute(
                job(ReasoningJob.JobType.REASONER_CONSISTENCY, "HERMIT", null));
        assertEquals(Set.of("success", "consistent", "reasonerType", "projectId", "ontologyRevision"),
                consistency.keySet());
        assertEquals(true, consistency.get("consistent"));
    }

    @Test
    void classTreeMatchesTheOldHierarchyJobBuiltWithHermit() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor()
                .projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        ReasoningJobExecutor executor = executor(SnapshotTestSupport.sessions(editor));

        Map<String, Object> hierarchy = executor.execute(job(ReasoningJob.JobType.REASONER_HIERARCHY, "HERMIT", null));
        Map<String, Object> objectProperties = executor.execute(
                job(ReasoningJob.JobType.REASONER_OBJ_PROP_HIERARCHY, "HERMIT", null));

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(new StreamDocumentSource(
                new ByteArrayInputStream(SnapshotTestSupport.nTriples(SnapshotTestSupport.PIZZA, true)),
                IRI.create("urn:test:pizza"), new NTriplesDocumentFormat(), "application/n-triples"));
        OWLReasoner hermit = EphemeralReasonerFactory.create(ontology, ReasonerType.HERMIT, 120_000);
        try {
            OldHierarchy old = new OldHierarchy(ontology, hermit);
            Set<String> visited = new HashSet<>();
            List<Map<String, Object>> oldTree = old.classTree(visited);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> newTree = (List<Map<String, Object>>) hierarchy.get("hierarchy");
            assertEquals(normalize(oldTree), normalize(newTree));
            assertEquals(visited.size(), hierarchy.get("totalClasses"));
            assertTrue(ids(newTree).size() > 20);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> newProperties = (List<Map<String, Object>>) objectProperties.get("hierarchy");
            assertEquals(normalize(List.of(old.objectPropertyTree())), normalize(newProperties));
        } finally {
            hermit.dispose();
        }
    }

    @Test
    void inconsistentOntologyFailsTheTreeJobsAndReportsTheConflictElsewhere() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor()
                .project("P1", SnapshotTestSupport.INCONSISTENT);
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        ReasoningJobExecutor executor = executor(sessions);

        InconsistentOntologyException failure = assertThrows(InconsistentOntologyException.class,
                () -> executor.execute(job(ReasoningJob.JobType.REASONER_HIERARCHY, "HERMIT", null)));
        assertTrue(ReasoningFriendlyErrors.forUser(failure.getMessage()).toLowerCase().contains("inconsistent"));
        assertThrows(InconsistentOntologyException.class,
                () -> executor.execute(job(ReasoningJob.JobType.REASONER_OBJ_PROP_HIERARCHY, "HERMIT", null)));
        assertThrows(InconsistentOntologyException.class,
                () -> executor.execute(job(ReasoningJob.JobType.REASONER_DATA_PROP_HIERARCHY, "HERMIT", null)));

        Map<String, Object> classify = executor.execute(job(ReasoningJob.JobType.REASONER_CLASSIFY, "HERMIT", null));
        assertEquals(Set.of("success", "inconsistent", "issues", "message", "ontologyRevision"), classify.keySet());
        assertEquals(true, classify.get("inconsistent"));
        assertFalse(((List<?>) classify.get("issues")).isEmpty());

        Map<String, Object> inferred = executor.execute(
                job(ReasoningJob.JobType.REASONER_INFERRED_AXIOMS, "HERMIT", null));
        assertEquals(true, inferred.get("inconsistent"));

        Map<String, Object> snapshot = executor.execute(job(ReasoningJob.JobType.REASONER_SNAPSHOT, "HERMIT", null));
        assertEquals(false, snapshot.get("consistent"));
        assertEquals(true, ((Map<?, ?>) snapshot.get("inconsistency")).get("inconsistent"));

        Map<String, Object> consistency = executor.execute(
                job(ReasoningJob.JobType.REASONER_CONSISTENCY, "HERMIT", null));
        assertEquals(false, consistency.get("consistent"));
        assertTrue(consistency.containsKey("unsatisfiableClasses"));
        verify(sessions, times(1)).openSnapshotSession(any(), any(), any());
        verify(sessions, never()).openSession(any(), any(), any());
    }

    @Test
    void consistencyWithoutASnapshotUsesTheQuickCheckAndCachesNothing() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor()
                .projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(sessions);
        ReasoningJobExecutor executor = new ReasoningJobExecutor(sessions, snapshots);

        Map<String, Object> consistency = executor.execute(
                job(ReasoningJob.JobType.REASONER_CONSISTENCY, "OPENLLET", null));

        assertEquals(true, consistency.get("consistent"));
        assertEquals("Openllet", consistency.get("reasonerType"));
        verify(sessions, times(1)).openSession(any(), any(), any());
        verify(sessions, never()).openSnapshotSession(any(), any(), any());
        assertEquals(0, snapshots.size());
    }

    @Test
    void consistencyWaitsForASnapshotThatIsAlreadyBeingBuilt() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor()
                .projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger opened = new AtomicInteger();
        doAnswer(invocation -> {
            opened.incrementAndGet();
            started.countDown();
            release.await();
            return invocation.callRealMethod();
        }).when(sessions).openSnapshotSession(any(), any(), any());
        ReasoningJobExecutor executor = executor(sessions);

        Future<Map<String, Object>> snapshot = pool.submit(() ->
                executor.execute(job(ReasoningJob.JobType.REASONER_SNAPSHOT, "OPENLLET", null)));
        assertTrue(started.await(10, TimeUnit.SECONDS));
        Future<Map<String, Object>> consistency = pool.submit(() ->
                executor.execute(job(ReasoningJob.JobType.REASONER_CONSISTENCY, "OPENLLET", null)));
        Thread.sleep(300);
        assertFalse(consistency.isDone());
        release.countDown();

        assertEquals(true, consistency.get(60, TimeUnit.SECONDS).get("consistent"));
        assertEquals(true, snapshot.get(60, TimeUnit.SECONDS).get("consistent"));
        assertEquals(1, opened.get());
        verify(sessions, never()).openSession(any(), any(), any());
    }

    private static ReasoningJobExecutor executor(OntologySessionService sessions) {
        return new ReasoningJobExecutor(sessions, SnapshotTestSupport.snapshots(sessions));
    }

    private static ReasoningJob job(ReasoningJob.JobType type, String reasoner, String owner) {
        return ReasoningJob.builder()
                .jobId("job-" + type.name())
                .jobType(type)
                .projectId("P1")
                .reasonerType(reasoner)
                .ownerEmail(owner)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> normalize(List<Map<String, Object>> nodes) {
        List<Object> normalized = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("keys", new TreeSet<>(node.keySet()));
            copy.put("id", node.get("id"));
            copy.put("label", node.get("label"));
            copy.put("hasChildren", node.get("hasChildren"));
            copy.put("type", node.get("type"));
            copy.put("isUnsatisfiable", node.get("isUnsatisfiable"));
            for (String equivalentKey : List.of("equivalentClasses", "equivalentProperties")) {
                Object equivalents = node.get(equivalentKey);
                if (equivalents != null) {
                    copy.put(equivalentKey, ((List<Map<String, Object>>) equivalents).stream()
                            .map(e -> e.get("iri").toString())
                            .collect(Collectors.toCollection(TreeSet::new)));
                }
            }
            copy.put("children", normalize((List<Map<String, Object>>) node.get("children")));
            normalized.add(copy);
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private static Set<Object> ids(List<Map<String, Object>> nodes) {
        Set<Object> ids = new HashSet<>();
        for (Map<String, Object> node : nodes) {
            ids.add(node.get("id"));
            ids.addAll(ids((List<Map<String, Object>>) node.get("children")));
        }
        return ids;
    }

    private static final class OldHierarchy {
        private final OWLOntology ontology;
        private final OWLReasoner reasoner;
        private final OWLDataFactory df;

        OldHierarchy(OWLOntology ontology, OWLReasoner reasoner) {
            this.ontology = ontology;
            this.reasoner = reasoner;
            this.df = ontology.getOWLOntologyManager().getOWLDataFactory();
        }

        List<Map<String, Object>> classTree(Set<String> visited) {
            reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY, InferenceType.OBJECT_PROPERTY_HIERARCHY,
                    InferenceType.DATA_PROPERTY_HIERARCHY);
            List<Map<String, Object>> hierarchy = new ArrayList<>();
            hierarchy.add(classNode(df.getOWLThing(), visited, 3));
            if (reasoner.getUnsatisfiableClasses().getSize() > 1
                    || !reasoner.getSubClasses(df.getOWLNothing(), true).isEmpty()) {
                hierarchy.add(classNode(df.getOWLNothing(), visited, 3));
            }
            return hierarchy;
        }

        private Map<String, Object> classNode(OWLClass owlClass, Set<String> visited, int maxDepth) {
            String iri = owlClass.getIRI().toString();
            List<Map<String, String>> equivalentClasses = reasoner.getEquivalentClasses(owlClass).getEntities().stream()
                    .filter(cls -> !cls.equals(owlClass))
                    .map(cls -> Map.of("iri", cls.getIRI().toString(), "label", label(cls)))
                    .collect(Collectors.toList());
            if (visited.contains(iri) && !owlClass.isOWLThing() && !owlClass.isOWLNothing()) {
                return Map.of("id", iri, "label", label(owlClass),
                        "children", List.of(), "hasChildren", false, "equivalentClasses", equivalentClasses);
            }
            visited.add(iri);
            NodeSet<OWLClass> subClasses = reasoner.getSubClasses(owlClass, true);
            boolean hasAnyChildren = subClasses.getFlattened().stream()
                    .anyMatch(c -> !c.isOWLNothing() && !c.equals(owlClass));
            List<Map<String, Object>> children = new ArrayList<>();
            if (maxDepth > 0) {
                for (Node<OWLClass> subClassNode : subClasses) {
                    OWLClass representative = subClassNode.getRepresentativeElement();
                    if (representative.isOWLNothing() && !owlClass.isOWLThing()) continue;
                    if (representative.equals(owlClass)) continue;
                    children.add(classNode(representative, visited, maxDepth - 1));
                }
                children.sort(Comparator.comparing(m -> m.get("label").toString()));
            }
            Map<String, Object> node = new HashMap<>();
            node.put("id", iri);
            node.put("label", label(owlClass));
            node.put("children", children);
            node.put("hasChildren", hasAnyChildren);
            node.put("type", "Class");
            node.put("equivalentClasses", equivalentClasses);
            if (owlClass.isOWLNothing() || !reasoner.isSatisfiable(owlClass)) {
                node.put("isUnsatisfiable", true);
            }
            return node;
        }

        Map<String, Object> objectPropertyTree() {
            return objectPropertyNode(df.getOWLTopObjectProperty(), new HashSet<>());
        }

        private Map<String, Object> objectPropertyNode(OWLObjectProperty property, Set<String> visited) {
            String iri = property.getIRI().toString();
            List<Map<String, String>> equivalentProperties =
                    reasoner.getEquivalentObjectProperties(property).getEntities().stream()
                            .filter(p -> !p.equals(property) && !p.isAnonymous())
                            .map(p -> Map.of("iri", p.asOWLObjectProperty().getIRI().toString(),
                                    "label", label(p.asOWLObjectProperty())))
                            .collect(Collectors.toList());
            if (visited.contains(iri) && !property.isOWLTopObjectProperty()) {
                return Map.of("id", iri, "label", label(property),
                        "children", List.of(), "hasChildren", false, "equivalentProperties", equivalentProperties);
            }
            visited.add(iri);
            List<Map<String, Object>> children = new ArrayList<>();
            for (Node<OWLObjectPropertyExpression> node : reasoner.getSubObjectProperties(property, true)) {
                OWLObjectPropertyExpression rep = node.getRepresentativeElement();
                if (rep.isAnonymous()) continue;
                OWLObjectProperty sub = rep.asOWLObjectProperty();
                if (sub.isOWLBottomObjectProperty() || sub.equals(property)) continue;
                children.add(objectPropertyNode(sub, visited));
            }
            children.sort(Comparator.comparing(m -> m.get("label").toString()));
            Map<String, Object> result = new HashMap<>();
            result.put("id", iri);
            result.put("label", label(property));
            result.put("children", children);
            result.put("hasChildren", !children.isEmpty());
            result.put("type", "ObjectProperty");
            result.put("equivalentProperties", equivalentProperties);
            return result;
        }

        private String label(org.semanticweb.owlapi.model.OWLEntity entity) {
            return ReasoningSnapshot.label(entity, ontology);
        }
    }
}
