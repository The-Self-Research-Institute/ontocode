package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditOperation;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.InferredAxiomInput;
import self.research.ontology.owlEditor.service.AssistantSwrlAxiomInsertionService.DerivedEdit;
import self.research.ontology.owlEditor.service.AssistantSwrlAxiomInsertionService.InsertionDerivation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class AssistantSwrlAxiomInsertionServiceTest {

    private static final String NS = "http://ex.org/pizza#";

    @Mock
    private StorageManager storageManager;

    @Mock
    private SparqlDatasetService datasetService;

    private FakeAssistantGraph graph;
    private AssistantSwrlAxiomInsertionService insertionService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        insertionService = new AssistantSwrlAxiomInsertionService(storageManager,
                new AssistantGraphIdentifierLookup(datasetService));
        graph = FakeAssistantGraph.installOn(datasetService);
    }

    private void stubTotalLines(long totalLines) throws Exception {
        when(storageManager.readCodeViewPage(eq("proj-1"), eq("turtle"), eq(0L), eq(0)))
                .thenReturn(new StorageManager.CodeViewPage("", 0, 0, totalLines, 0));
    }

    @Test
    void unsupportedOperationTypeIsRefused() {
        EditOperation operation = new EditOperation("rename_identifier", "turtle", null, null, List.of());

        InsertionDerivation result = insertionService.derive("proj-1", operation, 200);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("add_inferred_axioms"), result.detail());
    }

    @Test
    void missingTargetPathIsRefused() {
        EditOperation operation = new EditOperation("add_inferred_axioms", null, null, null,
                List.of(classAssertion(NS + "pepperoni", NS + "Topping")));

        InsertionDerivation result = insertionService.derive("proj-1", operation, 200);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("targetPath"), result.detail());
    }

    @Test
    void unsupportedFormatIsRefused() {
        EditOperation operation = new EditOperation("add_inferred_axioms", "rdfxml", null, null,
                List.of(classAssertion(NS + "pepperoni", NS + "Topping")));

        InsertionDerivation result = insertionService.derive("proj-1", operation, 200);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("turtle and ntriples"), result.detail());
    }

    @Test
    void noAxiomsSuppliedIsRefused() {
        EditOperation operation = new EditOperation("add_inferred_axioms", "turtle", null, null, List.of());

        InsertionDerivation result = insertionService.derive("proj-1", operation, 200);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("No inferred axioms"), result.detail());
    }

    @Test
    void tooManyAxiomsIsRefused() {
        List<InferredAxiomInput> axioms = List.of(
                classAssertion(NS + "a", NS + "Topping"), classAssertion(NS + "b", NS + "Topping"));
        EditOperation operation = new EditOperation("add_inferred_axioms", "turtle", null, null, axioms);

        InsertionDerivation result = insertionService.derive("proj-1", operation, 1);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("Too many"), result.detail());
    }

    @Test
    void rendersClassAssertionAsNewTripleLineAppendedAtEndOfFile() throws Exception {
        graph.existing.add(NS + "pepperoni");
        graph.existing.add(NS + "Topping");
        stubTotalLines(12);
        EditOperation operation = new EditOperation("add_inferred_axioms", "turtle", null, null,
                List.of(classAssertion(NS + "pepperoni", NS + "Topping")));

        InsertionDerivation result = insertionService.derive("proj-1", operation, 200);

        assertTrue(result.ok(), result.detail());
        assertEquals(1, result.axiomCount());
        DerivedEdit edit = result.edits().get(0);
        assertEquals(12, edit.line());
        assertEquals("", edit.originalText());
        assertEquals("<" + NS + "pepperoni> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <" + NS
                + "Topping> .\n", edit.newText());
    }

    @Test
    void rendersDataPropertyAssertionWithLiteralAndDatatype() throws Exception {
        graph.existing.add(NS + "pepperoni");
        graph.existing.add(NS + "spicinessLevel");
        stubTotalLines(5);
        InferredAxiomInput axiom = new InferredAxiomInput("DataPropertyAssertion", NS + "pepperoni",
                NS + "spicinessLevel", null, "7", "http://www.w3.org/2001/XMLSchema#integer", null);
        EditOperation operation = new EditOperation("add_inferred_axioms", "turtle", null, null, List.of(axiom));

        InsertionDerivation result = insertionService.derive("proj-1", operation, 200);

        assertTrue(result.ok(), result.detail());
        assertEquals("<" + NS + "pepperoni> <" + NS + "spicinessLevel> \"7\"^^<http://www.w3.org/2001/XMLSchema#integer> .\n",
                result.edits().get(0).newText());
    }

    @Test
    void blankObjectIriFallsThroughToTheLiteralInsteadOfBeingTreatedAsARealObject() throws Exception {
        graph.existing.add(NS + "pepperoni");
        graph.existing.add(NS + "spicinessLevel");
        stubTotalLines(1);
        InferredAxiomInput axiom = new InferredAxiomInput("DataPropertyAssertion", NS + "pepperoni",
                NS + "spicinessLevel", "", "7", null, null);
        EditOperation operation = new EditOperation("add_inferred_axioms", "turtle", null, null, List.of(axiom));

        InsertionDerivation result = insertionService.derive("proj-1", operation, 200);

        assertTrue(result.ok(), result.detail());
        assertEquals("<" + NS + "pepperoni> <" + NS + "spicinessLevel> \"7\" .\n", result.edits().get(0).newText());
    }

    @Test
    void escapesCarriageReturnsInLiteralsSoTheTurtleStaysValid() throws Exception {
        graph.existing.add(NS + "pepperoni");
        graph.existing.add(NS + "notes");
        stubTotalLines(2);
        InferredAxiomInput axiom = new InferredAxiomInput("DataPropertyAssertion", NS + "pepperoni",
                NS + "notes", null, "line1\r\nline2", null, null);
        EditOperation operation = new EditOperation("add_inferred_axioms", "turtle", null, null, List.of(axiom));

        InsertionDerivation result = insertionService.derive("proj-1", operation, 200);

        assertTrue(result.ok(), result.detail());
        assertEquals("<" + NS + "pepperoni> <" + NS + "notes> \"line1\\r\\nline2\" .\n", result.edits().get(0).newText());
    }

    @Test
    void skipsAxiomWhoseSubjectDoesNotExistButStillAddsTheRest() throws Exception {
        graph.existing.add(NS + "pepperoni");
        graph.existing.add(NS + "Topping");
        stubTotalLines(3);
        EditOperation operation = new EditOperation("add_inferred_axioms", "turtle", null, null, List.of(
                classAssertion(NS + "ghost", NS + "Topping"),
                classAssertion(NS + "pepperoni", NS + "Topping")));

        InsertionDerivation result = insertionService.derive("proj-1", operation, 200);

        assertTrue(result.ok(), result.detail());
        assertEquals(1, result.axiomCount());
        assertTrue(result.detail().contains("1 skipped"), result.detail());
        assertEquals("<" + NS + "pepperoni> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <" + NS
                + "Topping> .\n", result.edits().get(0).newText());
    }

    @Test
    void skipsAxiomWhosePredicateDoesNotExistInTheGraph() throws Exception {
        graph.existing.add(NS + "pepperoni");
        stubTotalLines(4);
        InferredAxiomInput axiom = new InferredAxiomInput("DataPropertyAssertion", NS + "pepperoni",
                NS + "madeUpProperty", null, "7", null, null);
        EditOperation operation = new EditOperation("add_inferred_axioms", "turtle", null, null, List.of(axiom));

        InsertionDerivation result = insertionService.derive("proj-1", operation, 200);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("does not exist in the graph"), result.detail());
    }

    @Test
    void refusesWhenNoneOfTheAxiomsCanBeRendered() {
        EditOperation operation = new EditOperation("add_inferred_axioms", "turtle", null, null,
                List.of(classAssertion(NS + "ghost", NS + "Topping")));

        InsertionDerivation result = insertionService.derive("proj-1", operation, 200);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("does not exist in the graph"), result.detail());
    }

    @Test
    void graphLookupFailureIsRefused() {
        graph.failure = new RuntimeException("dataset unavailable");
        EditOperation operation = new EditOperation("add_inferred_axioms", "turtle", null, null,
                List.of(classAssertion(NS + "pepperoni", NS + "Topping")));

        InsertionDerivation result = insertionService.derive("proj-1", operation, 200);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("Could not confirm"), result.detail());
    }

    private InferredAxiomInput classAssertion(String subjectIri, String classIri) {
        return new InferredAxiomInput("ClassAssertion", subjectIri,
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#type", classIri, null, null, null);
    }
}
