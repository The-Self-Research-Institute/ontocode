package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditOperation;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.FuzzyMembershipInput;
import self.research.ontology.owlEditor.service.AssistantFuzzyMembershipInsertionService.DerivedEdit;
import self.research.ontology.owlEditor.service.AssistantFuzzyMembershipInsertionService.InsertionDerivation;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class AssistantFuzzyMembershipInsertionServiceTest {

    private static final String NS = "http://ex.org/pizza#";

    @Mock
    private StorageManager storageManager;

    @Mock
    private SparqlDatasetService datasetService;

    @Mock
    private FuzzyMembershipQueryService membershipQueryService;

    private FakeAssistantGraph graph;
    private AssistantFuzzyMembershipInsertionService insertionService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        insertionService = new AssistantFuzzyMembershipInsertionService(storageManager,
                new AssistantGraphIdentifierLookup(datasetService), membershipQueryService);
        graph = FakeAssistantGraph.installOn(datasetService);
        when(membershipQueryService.forIndividual(eq("proj-1"), anyString())).thenReturn(List.of());
    }

    private void stubTotalLines(long totalLines) throws Exception {
        when(storageManager.readCodeViewPage(eq("proj-1"), eq("turtle"), eq(0L), eq(0)))
                .thenReturn(new StorageManager.CodeViewPage("", 0, 0, totalLines, 0));
    }

    private FuzzyMembershipInput membership(String entityIri, String classIri, Double degree) {
        return new FuzzyMembershipInput(entityIri, classIri, degree);
    }

    @Test
    void unsupportedOperationTypeIsRefused() {
        EditOperation operation = new EditOperation("rename_identifier", "turtle", null, null, null, List.of());

        InsertionDerivation result = insertionService.derive("proj-1", operation, 50);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("add_fuzzy_membership"), result.detail());
    }

    @Test
    void missingTargetPathIsRefused() {
        EditOperation operation = new EditOperation("add_fuzzy_membership", null, null, null, null,
                List.of(membership(NS + "alice", NS + "Diabetic", 0.9)));

        InsertionDerivation result = insertionService.derive("proj-1", operation, 50);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("targetPath"), result.detail());
    }

    @Test
    void unsupportedFormatIsRefused() {
        EditOperation operation = new EditOperation("add_fuzzy_membership", "rdfxml", null, null, null,
                List.of(membership(NS + "alice", NS + "Diabetic", 0.9)));

        InsertionDerivation result = insertionService.derive("proj-1", operation, 50);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("turtle and ntriples"), result.detail());
    }

    @Test
    void noMembershipsSuppliedIsRefused() {
        EditOperation operation = new EditOperation("add_fuzzy_membership", "turtle", null, null, null, List.of());

        InsertionDerivation result = insertionService.derive("proj-1", operation, 50);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("No fuzzy memberships"), result.detail());
    }

    @Test
    void tooManyMembershipsIsRefused() {
        List<FuzzyMembershipInput> memberships = List.of(
                membership(NS + "a", NS + "C", 0.5), membership(NS + "b", NS + "C", 0.5));
        EditOperation operation = new EditOperation("add_fuzzy_membership", "turtle", null, null, null, memberships);

        InsertionDerivation result = insertionService.derive("proj-1", operation, 1);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("Too many"), result.detail());
    }

    @Test
    void degreeOutOfRangeIsSkipped() {
        graph.existing.add(NS + "alice");
        graph.existing.add(NS + "Diabetic");
        EditOperation operation = new EditOperation("add_fuzzy_membership", "turtle", null, null, null,
                List.of(membership(NS + "alice", NS + "Diabetic", 1.5)));

        InsertionDerivation result = insertionService.derive("proj-1", operation, 50);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("not between 0.0 and 1.0"), result.detail());
    }

    @Test
    void nonExistentEntityIsSkipped() {
        graph.existing.add(NS + "Diabetic");
        EditOperation operation = new EditOperation("add_fuzzy_membership", "turtle", null, null, null,
                List.of(membership(NS + "ghost", NS + "Diabetic", 0.9)));

        InsertionDerivation result = insertionService.derive("proj-1", operation, 50);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("does not exist in the graph"), result.detail());
    }

    @Test
    void duplicateMembershipIsRejectedWithFuzzyTabPointer() {
        graph.existing.add(NS + "alice");
        graph.existing.add(NS + "Diabetic");
        when(membershipQueryService.forIndividual("proj-1", NS + "alice"))
                .thenReturn(List.of(Map.of("classIri", NS + "Diabetic", "degree", 0.7)));
        EditOperation operation = new EditOperation("add_fuzzy_membership", "turtle", null, null, null,
                List.of(membership(NS + "alice", NS + "Diabetic", 0.9)));

        InsertionDerivation result = insertionService.derive("proj-1", operation, 50);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("Fuzzy plugin"), result.detail());
    }

    @Test
    void duplicatePairWithinTheSameBatchIsRejectedNotInsertedTwice() throws Exception {
        graph.existing.add(NS + "alice");
        graph.existing.add(NS + "Diabetic");
        stubTotalLines(2);
        EditOperation operation = new EditOperation("add_fuzzy_membership", "turtle", null, null, null, List.of(
                membership(NS + "alice", NS + "Diabetic", 0.3),
                membership(NS + "alice", NS + "Diabetic", 0.9)));

        InsertionDerivation result = insertionService.derive("proj-1", operation, 50);

        assertTrue(result.ok(), result.detail());
        assertEquals(1, result.membershipCount());
        assertTrue(result.detail().contains("duplicate membership in this same request"), result.detail());
    }

    @Test
    void happyPathRendersThreeLineBlankNodeBlock() throws Exception {
        graph.existing.add(NS + "alice");
        graph.existing.add(NS + "Diabetic");
        stubTotalLines(7);
        EditOperation operation = new EditOperation("add_fuzzy_membership", "turtle", null, null, null,
                List.of(membership(NS + "alice", NS + "Diabetic", 0.9)));

        InsertionDerivation result = insertionService.derive("proj-1", operation, 50);

        assertTrue(result.ok(), result.detail());
        assertEquals(1, result.membershipCount());
        DerivedEdit edit = result.edits().get(0);
        assertEquals(7, edit.line());
        assertEquals("", edit.originalText());
        String[] lines = edit.newText().split("\n");
        assertEquals(3, lines.length);
        assertTrue(lines[0].matches("<" + NS + "alice> <http://fuzzy\\.org/ontology#hasMembership> _:fm\\w+ \\."));
        String blankNode = lines[0].split(" ")[2];
        assertEquals(blankNode + " <http://fuzzy.org/ontology#inClass> <" + NS + "Diabetic> .", lines[1]);
        assertEquals(blankNode + " <http://fuzzy.org/ontology#degree> \"0.9\"^^<http://www.w3.org/2001/XMLSchema#double> .",
                lines[2]);
    }

    @Test
    void twoMembershipsInOneBatchGetDistinctBlankNodeLabels() throws Exception {
        graph.existing.add(NS + "alice");
        graph.existing.add(NS + "Diabetic");
        graph.existing.add(NS + "HeartDisease");
        stubTotalLines(3);
        EditOperation operation = new EditOperation("add_fuzzy_membership", "turtle", null, null, null, List.of(
                membership(NS + "alice", NS + "Diabetic", 0.9),
                membership(NS + "alice", NS + "HeartDisease", 0.4)));

        InsertionDerivation result = insertionService.derive("proj-1", operation, 50);

        assertTrue(result.ok(), result.detail());
        assertEquals(2, result.membershipCount());
        String text = result.edits().get(0).newText();
        String[] lines = text.split("\n");
        assertEquals(6, lines.length);
        String firstBlank = lines[0].split(" ")[2];
        String secondBlank = lines[3].split(" ")[2];
        assertFalse(firstBlank.equals(secondBlank), "each membership must get its own blank-node label");
    }
}
