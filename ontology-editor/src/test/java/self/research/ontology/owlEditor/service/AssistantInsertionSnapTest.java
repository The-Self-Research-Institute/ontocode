package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditGroupInput;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditInput;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditRange;
import self.research.ontology.owlEditor.service.AssistantEditProposalService.GroupProposalOutcome;
import self.research.ontology.owlEditor.util.SubjectRangeIndex;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class AssistantInsertionSnapTest extends AssistantEditProposalTestBase {

    private static final String DOC = String.join("\n",
            "@prefix : <http://example.org/> .",
            "@prefix owl: <http://www.w3.org/2002/07/owl#> .",
            "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .",
            ":PizzaSize a owl:Class ;",
            "    rdfs:label \"PizzaSize\" ;",
            "    rdfs:comment \"Size. of a pizza\" .",
            ":Small a owl:Class .",
            "");

    @BeforeEach
    void realDocument() throws Exception {
        Path doc = write("pizza.ttl", DOC);
        when(storageManager.ensureCodeViewFile(anyString(), anyString())).thenReturn(doc);
        LineRangeSpliceWriter realWriter = new LineRangeSpliceWriter();
        when(spliceWriter.splice(any(), anyString(), any()))
                .thenAnswer(inv -> realWriter.splice(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
    }

    private GroupProposalOutcome proposeInsert(long line, String text) {
        EditInput edit = new EditInput("turtle", new EditRange(line, 0), "", text);
        return proposalService.propose("s1", "u@x.com", List.of(new EditGroupInput("c1", List.of(edit))))
                .getGroups().get(0);
    }

    @Test
    void anInsertThatSplitsAStatementMovesToTheEndOfThatStatement() {
        GroupProposalOutcome outcome = proposeInsert(5, ":Crust a owl:Class .");

        assertTrue(outcome.isValidationPassed());
        assertEquals(6L, outcome.getDiff().get(0).startLine());
        assertEquals("Moved from line 6 to line 7 so it doesn't split a statement.",
                checkNamed(outcome, AssistantEditProposalService.INSERTION_MOVED_CHECK).get().detail());
    }

    @Test
    void anInsertAlreadyBetweenStatementsIsLeftWhereItIs() {
        GroupProposalOutcome outcome = proposeInsert(6, ":Crust a owl:Class .");

        assertTrue(outcome.isValidationPassed());
        assertEquals(6L, outcome.getDiff().get(0).startLine());
        assertTrue(checkNamed(outcome, AssistantEditProposalService.INSERTION_MOVED_CHECK).isEmpty());
    }

    @Test
    void brokenContentStillFailsInsteadOfBeingMoved() {
        GroupProposalOutcome outcome = proposeInsert(5, ":Crust a owl:Class ;");

        assertFalse(outcome.isValidationPassed());
        assertEquals(5L, outcome.getDiff().get(0).startLine());
        assertFalse(checkNamed(outcome, "syntax_valid").get().passed());
        assertTrue(checkNamed(outcome, AssistantEditProposalService.INSERTION_MOVED_CHECK).isEmpty());
    }

    @Test
    void replacementsAreNeverMoved() throws Exception {
        mockLiveContent("turtle", 4, 1, "    rdfs:label \"PizzaSize\" ;");
        EditInput edit = new EditInput("turtle", new EditRange(4, 1), "    rdfs:label \"PizzaSize\" ;", "    rdfs:label \"Size\" .");
        GroupProposalOutcome outcome = proposalService.propose("s1", "u@x.com",
                List.of(new EditGroupInput("c1", List.of(edit)))).getGroups().get(0);

        assertFalse(outcome.isValidationPassed());
        assertEquals(4L, outcome.getDiff().get(0).startLine());
        assertTrue(checkNamed(outcome, AssistantEditProposalService.INSERTION_MOVED_CHECK).isEmpty());
    }

    @Test
    void boundaryIsTheEndOfTheStatementContainingTheInsertLine() {
        SubjectRangeIndex index = SubjectRangeIndex.of(List.of(
                new SubjectRangeIndex.Block("http://example.org/A", 3, 5),
                new SubjectRangeIndex.Block("http://example.org/B", 6, 6)), Map.of(), "", "", Set.of(), true);

        assertEquals(OptionalLong.of(6), AssistantInsertionSnapper.boundaryAfter(index, 4));
        assertEquals(OptionalLong.of(6), AssistantInsertionSnapper.boundaryAfter(index, 5));
        assertTrue(AssistantInsertionSnapper.boundaryAfter(index, 3).isEmpty());
        assertTrue(AssistantInsertionSnapper.boundaryAfter(index, 6).isEmpty());
        assertTrue(AssistantInsertionSnapper.boundaryAfter(index, 7).isEmpty());
    }
}
