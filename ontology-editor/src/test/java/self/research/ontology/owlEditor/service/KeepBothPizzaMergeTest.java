package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import self.research.ontology.owlEditor.dto.OntologyDto;
import self.research.ontology.owlEditor.hierarchy.HierarchySnapshotBuilder;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.merge.MergeCopy;
import self.research.ontology.owlEditor.model.merge.MergeOptions;
import self.research.ontology.owlEditor.model.merge.MergeResult;
import self.research.ontology.owlEditor.model.merge.MergeStrategy;
import self.research.ontology.owlEditor.service.delta.DeltaSparql;
import self.research.ontology.owlEditor.service.delta.ExactDelta;
import self.research.ontology.owlEditor.service.delta.ExactReversal;
import self.research.ontology.owlEditor.util.OWLFormatConverter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static self.research.ontology.owlEditor.service.KeepBothPizzaFixture.ENTITY;
import static self.research.ontology.owlEditor.service.KeepBothPizzaFixture.P;
import static self.research.ontology.owlEditor.service.KeepBothPizzaFixture.SUFFIX;

class KeepBothPizzaMergeTest {

    @Mock
    private SparqlDatasetService datasetService;
    @Mock
    private ProjectImportService importService;
    @Mock
    private StorageManager storageManager;
    @Mock
    private ProjectMetadataService metadataService;
    @Mock
    private OntologyHistoryService historyService;

    private OntologyMergeService service;
    private Path targetFile;
    private Path sourceFile;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        service = new OntologyMergeService(datasetService, importService, storageManager, metadataService, historyService);
        targetFile = dir.resolve("target.owl");
        sourceFile = dir.resolve("source.ttl");
        KeepBothPizzaFixture.writeTarget(targetFile);
        KeepBothPizzaFixture.writeSource(sourceFile);
        when(storageManager.findCurrentOntology("proj")).thenReturn(Optional.of(targetFile));
        when(metadataService.readMeta(anyString())).thenReturn(Optional.empty());
        when(importService.trySubmitImport(anyString(), any())).thenReturn(true);
    }

    private MergeResult mergeKeepingBoth() throws Exception {
        MergeOptions options = new MergeOptions();
        options.setStrategy(MergeStrategy.KEEP_BOTH);
        return service.mergeOntologies("src", sourceFile, "proj", null, null, options, "u1", "User");
    }

    private OWLOntology merged() throws Exception {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(targetFile.toFile());
    }

    private static Set<String> iris(Set<? extends OWLEntity> entities) {
        return entities.stream().map(e -> e.getIRI().toString()).collect(Collectors.toSet());
    }

    private static Set<String> labels(OWLOntology ontology, String iri) {
        return ontology.annotationAssertionAxioms(IRI.create(iri))
                .filter(axiom -> axiom.getProperty().isLabel())
                .map(axiom -> {
                    OWLLiteral literal = axiom.getValue().asLiteral().orElseThrow();
                    return literal.hasLang() ? literal.getLiteral() + "@" + literal.getLang() : literal.getLiteral();
                })
                .collect(Collectors.toSet());
    }

    private static Set<String> namedParents(OWLOntology ontology, String iri) {
        OWLClass cls = ontology.getOWLOntologyManager().getOWLDataFactory().getOWLClass(IRI.create(iri));
        return ontology.subClassAxiomsForSubClass(cls)
                .map(axiom -> axiom.getSuperClass())
                .filter(OWLClassExpression::isNamed)
                .map(sup -> sup.asOWLClass().getIRI().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void everyClassPropertyAndIndividualInBothFilesGetsACopy() throws Exception {
        mergeKeepingBoth();
        OWLOntology ontology = merged();

        assertTrue(iris(ontology.getClassesInSignature()).containsAll(Set.of(P + "PizzaCheese" + SUFFIX, P + "Cheese" + SUFFIX,
                ENTITY + SUFFIX, P + "Pizza" + SUFFIX, P + "Mystery" + SUFFIX)));
        assertTrue(iris(ontology.getClassesInSignature()).containsAll(Set.of(P + "PizzaCheese", P + "Cheese", ENTITY,
                P + "Pizza", P + "Mystery", P + "TargetOnly")));
        assertTrue(iris(ontology.getObjectPropertiesInSignature()).contains(P + "hasTopping" + SUFFIX));
        assertTrue(iris(ontology.getDataPropertiesInSignature()).contains(P + "hasCalories" + SUFFIX));
        assertTrue(iris(ontology.getIndividualsInSignature()).contains(P + "Margherita" + SUFFIX));
    }

    @Test
    void anItemOnlyInTheMergedFileIsAddedWithoutACopy() throws Exception {
        mergeKeepingBoth();
        Set<String> classes = iris(merged().getClassesInSignature());

        assertTrue(classes.contains(P + "PizzaTopping"));
        assertTrue(classes.contains(P + "Mozzarella"));
        assertFalse(classes.contains(P + "PizzaTopping" + SUFFIX));
        assertFalse(classes.contains(P + "Mozzarella" + SUFFIX));
        assertFalse(classes.contains(P + "TargetOnly" + SUFFIX));
    }

    @Test
    void annotationPropertiesDatatypesAndStandardWordsAreNeverCopied() throws Exception {
        mergeKeepingBoth();
        OWLOntology ontology = merged();

        assertTrue(iris(ontology.getAnnotationPropertiesInSignature()).contains(P + "definition"));
        assertTrue(iris(ontology.getAnnotationPropertiesInSignature()).stream().noneMatch(iri -> iri.endsWith(SUFFIX)));
        assertTrue(iris(ontology.getDatatypesInSignature()).stream().noneMatch(iri -> iri.endsWith(SUFFIX)));
        assertTrue(iris(ontology.getSignature()).stream()
                .noneMatch(iri -> iri.startsWith("http://www.w3.org/") && iri.endsWith(SUFFIX)));
        assertTrue(ontology.dataPropertyRangeAxioms(ontology.getOWLOntologyManager().getOWLDataFactory()
                .getOWLDataProperty(IRI.create(P + "hasCalories" + SUFFIX)))
                .anyMatch(axiom -> axiom.getRange().toString().contains(P + "Percent")));
        assertTrue(ontology.annotationAssertionAxioms(IRI.create(P + "Cheese" + SUFFIX))
                .anyMatch(axiom -> axiom.getProperty().getIRI().toString().equals(P + "definition")));
    }

    @Test
    void aCopyIsLabelledWithTheMergedFilesOwnLabelPlusTheSuffix() throws Exception {
        mergeKeepingBoth();
        OWLOntology ontology = merged();

        assertEquals(Set.of("Pizza cheese_imported@en"), labels(ontology, P + "PizzaCheese" + SUFFIX));
        assertEquals(Set.of("Cheese_imported"), labels(ontology, P + "Cheese" + SUFFIX));
        assertEquals(Set.of("Pizza_imported"), labels(ontology, P + "Pizza" + SUFFIX));
        assertEquals(Set.of("has topping_imported"), labels(ontology, P + "hasTopping" + SUFFIX));
    }

    @Test
    void aCopyOfAnItemTheMergedFileOnlyMentionsTakesTheUsersLabel() throws Exception {
        mergeKeepingBoth();

        assertEquals(Set.of("entity_imported"), labels(merged(), ENTITY + SUFFIX));
    }

    @Test
    void aCopyWithNoLabelInEitherFileIsLabelledWithItsShortName() throws Exception {
        mergeKeepingBoth();
        OWLOntology ontology = merged();

        assertEquals(Set.of("Mystery_imported"), labels(ontology, P + "Mystery" + SUFFIX));
        assertEquals(Set.of("Margherita_imported"), labels(ontology, P + "Margherita" + SUFFIX));
        assertEquals(Set.of("hasCalories_imported"), labels(ontology, P + "hasCalories" + SUFFIX));
    }

    @Test
    void theOriginalsKeepOnlyTheirOwnLabels() throws Exception {
        mergeKeepingBoth();
        OWLOntology ontology = merged();

        assertEquals(Set.of("Cheese"), labels(ontology, P + "PizzaCheese"));
        assertEquals(Set.of("Cheese"), labels(ontology, P + "Cheese"));
        assertEquals(Set.of("entity"), labels(ontology, ENTITY));
        assertEquals(Set.of(), labels(ontology, P + "Mystery"));
    }

    @Test
    void copiesKeepTheirRenamedParentsAndNewItemsHangUnderTheCopies() throws Exception {
        mergeKeepingBoth();
        OWLOntology ontology = merged();

        assertEquals(Set.of(P + "Pizza" + SUFFIX), namedParents(ontology, P + "PizzaCheese" + SUFFIX));
        assertEquals(Set.of(P + "Pizza"), namedParents(ontology, P + "PizzaCheese"));
        assertEquals(Set.of(ENTITY + SUFFIX), namedParents(ontology, P + "PizzaTopping"));
        assertEquals(Set.of(P + "Mystery" + SUFFIX), namedParents(ontology, P + "Mozzarella"));
        assertEquals(Set.of(), namedParents(ontology, ENTITY + SUFFIX));
    }

    @Test
    void theResultListsEveryCopyWithItsLabelAndKind() throws Exception {
        MergeResult result = mergeKeepingBoth();

        assertEquals(8, result.getEntitiesRenamed());
        assertEquals(8, result.getCopies().size());
        assertTrue(result.getCopies().contains(new MergeCopy(ENTITY, ENTITY + SUFFIX, "entity_imported", "Class")));
        assertTrue(result.getCopies().contains(
                new MergeCopy(P + "PizzaCheese", P + "PizzaCheese" + SUFFIX, "Pizza cheese_imported", "Class")));
        assertTrue(result.getCopies().contains(
                new MergeCopy(P + "hasTopping", P + "hasTopping" + SUFFIX, "has topping_imported", "ObjectProperty")));
        assertTrue(result.getCopies().contains(
                new MergeCopy(P + "hasCalories", P + "hasCalories" + SUFFIX, "hasCalories_imported", "DataProperty")));
        assertTrue(result.getCopies().contains(
                new MergeCopy(P + "Margherita", P + "Margherita" + SUFFIX, "Margherita_imported", "Individual")));
    }

    @Test
    void aParentlessCopyIsATopLevelClassInThePublishedTree() throws Exception {
        mergeKeepingBoth();
        OWLOntology ontology = merged();
        HierarchySnapshotBuilder builder = new HierarchySnapshotBuilder();
        OWLReasoner reasoner = new StructuralReasonerFactory().createNonBufferingReasoner(ontology);
        reasoner.precomputeInferences();

        for (List<OntologyDto.TreeNode> roots : List.of(builder.buildTopLevelAsserted(ontology, 500, 0),
                builder.buildTopLevel(ontology, reasoner, 500, 0))) {
            Map<String, String> shown = roots.stream()
                    .collect(Collectors.toMap(OntologyDto.TreeNode::getId, OntologyDto.TreeNode::getLabel));
            assertEquals("entity_imported", shown.get(ENTITY + SUFFIX));
            assertEquals("Cheese_imported", shown.get(P + "Cheese" + SUFFIX));
            assertEquals("Pizza_imported", shown.get(P + "Pizza" + SUFFIX));
            assertFalse(shown.containsKey(P + "PizzaCheese" + SUFFIX));
        }
        assertEquals(Set.of(P + "PizzaTopping"), builder.buildChildren(ontology, reasoner, ENTITY + SUFFIX, 500, 0).stream()
                .map(OntologyDto.TreeNode::getId).collect(Collectors.toSet()));
        assertEquals(Set.of(P + "PizzaCheese" + SUFFIX), builder.buildChildren(ontology, reasoner, P + "Pizza" + SUFFIX, 500, 0)
                .stream().map(OntologyDto.TreeNode::getId).collect(Collectors.toSet()));
    }

    private HistoryChange recordedMerge() throws Exception {
        mergeKeepingBoth();
        ArgumentCaptor<ChangeOrigin> origin = ArgumentCaptor.forClass(ChangeOrigin.class);
        verify(historyService).recordEdit(eq("proj"), eq("u1"), eq("User"), eq("ontologyMerge"), anyString(),
                eq("Ontology merge"), any(), any(), anyString(), any(), any(), eq(false), origin.capture());
        assertTrue(origin.getValue().exact() != null, "the merge recorded no exact change");
        Map<String, Object> stamped = new HashMap<>();
        origin.getValue().exact().stamp(stamped, "e1");
        HistoryChange change = new HistoryChange();
        change.setId("m1");
        change.setOperationType("ontologyMerge");
        change.setEntityIRI(OntologyMergeService.MERGE_IRI_PREFIX + "x");
        change.setExactDeleted((String) stamped.get("exactDeleted"));
        change.setExactInserted((String) stamped.get("exactInserted"));
        return change;
    }

    private static Model modelOf(Path file) throws Exception {
        return Rio.parse(Files.newInputStream(file), "", RDFFormat.RDFXML);
    }

    private static Model contentOf(SailRepository repository) {
        Model content = new org.eclipse.rdf4j.model.impl.LinkedHashModel();
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.getStatements(null, null, null).forEach(content::add);
        }
        return content;
    }

    private static void applyAll(SailRepository repository, RollbackMutationPlanner.Plan plan) {
        try (RepositoryConnection connection = repository.getConnection()) {
            for (String update : plan.rawUpdates()) {
                connection.prepareUpdate(update).execute();
            }
        }
    }

    @Test
    void theMergeUndoesAndRedoesExactly() throws Exception {
        Model original = modelOf(targetFile);
        HistoryChange change = recordedMerge();
        Model merged = modelOf(targetFile);
        assertFalse(Models.isomorphic(original, merged));

        SailRepository repository = new SailRepository(new MemoryStore());
        repository.init();
        try {
            try (RepositoryConnection connection = repository.getConnection()) {
                connection.add(merged);
            }
            RollbackMutationPlanner planner = new RollbackMutationPlanner();
            applyAll(repository, planner.planUndo(change, List.of()));
            assertTrue(Models.isomorphic(contentOf(repository), original), "undo did not return the original graph");
            applyAll(repository, planner.planRedo(change, List.of()));
            assertTrue(Models.isomorphic(contentOf(repository), merged), "redo did not bring the merge back");
        } finally {
            repository.shutDown();
        }
    }

    @Test
    void whatTheMergeRecordedIsInTheStoreAfterTheServerImportsTheFile() throws Exception {
        HistoryChange change = recordedMerge();
        Path prepared = dir.resolve("prepared.owl");
        Files.copy(targetFile, prepared);
        OWLFormatConverter.sanitizeFileOnDisk(prepared);
        SailRepository repository = new SailRepository(new MemoryStore());
        repository.init();
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(Rio.parse(Files.newInputStream(prepared), "", RDFFormat.RDFXML),
                    SimpleValueFactory.getInstance().createIRI("urn:test:project"));
            ExactDelta delta = ExactReversal.deltaOf(change).orElseThrow();
            for (String query : DeltaSparql.holds(delta, true, "urn:test:project")) {
                assertTrue(connection.prepareBooleanQuery(query).evaluate(),
                        "undo would be refused: the store does not hold what the merge recorded");
            }
        } finally {
            repository.shutDown();
        }
    }

    @Test
    void theMergeLeavesTheCountsToTheImportThatRebuildsTheTree() throws Exception {
        MergeResult result = mergeKeepingBoth();

        verify(importService).trySubmitImport(eq("proj"), eq(targetFile));
        verify(metadataService, never()).writeMeta(anyString(), any());
        assertFalse(result.isReimportSkipped());
    }

    @Test
    void aMergeWhoseImportWasRefusedSaysSoAndLeavesTheCountsAlone() throws Exception {
        when(importService.trySubmitImport(anyString(), any())).thenReturn(false);

        MergeResult result = mergeKeepingBoth();

        assertTrue(result.isSuccess());
        assertTrue(result.isReimportSkipped());
        verify(metadataService, never()).writeMeta(anyString(), any());
    }
}
