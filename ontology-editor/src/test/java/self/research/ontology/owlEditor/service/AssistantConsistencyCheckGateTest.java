package self.research.ontology.owlEditor.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditInput;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditRange;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantConsistencyCheckGateTest {

    private static EditInput edit(String newText) {
        return new EditInput("turtle", new EditRange(0, 1), "", newText);
    }

    @ParameterizedTest
    @CsvSource({
            "':A rdfs:subClassOf :B .', true",
            "':A owl:equivalentClass :B .', true",
            "':A owl:disjointWith :B .', true",
            "':p rdfs:domain :A .', true",
            "':p rdfs:range :A .', true",
            "'[] a owl:Restriction ; owl:onProperty :p ; owl:someValuesFrom :A .', true",
            "':A a owl:FunctionalProperty .', true",
    })
    void flagsEditsThatTouchAxioms(String newText, boolean expected) {
        assertEquals(expected, AssistantConsistencyCheckGate.worthChecking("turtle", List.of(edit(newText))));
    }

    @ParameterizedTest
    @CsvSource({
            "':A a owl:Class .', false",
            "':A rdfs:label \"Pizza\" .', false",
            "':A rdfs:comment \"A tasty dish.\" .', false",
    })
    void skipsPlainDeclarationsAndAnnotations(String newText, boolean expected) {
        assertEquals(expected, AssistantConsistencyCheckGate.worthChecking("turtle", List.of(edit(newText))));
    }

    @org.junit.jupiter.api.Test
    void stillFlagsWhenTheAxiomTokenIsOnlyInTheRemovedText() {
        EditInput removal = new EditInput("turtle", new EditRange(0, 1), ":A rdfs:subClassOf :B .", "");
        assertTrue(AssistantConsistencyCheckGate.worthChecking("turtle", List.of(removal)));
    }

    @org.junit.jupiter.api.Test
    void defaultsToWorthCheckingForAnUnrecognizedFormat() {
        assertTrue(AssistantConsistencyCheckGate.worthChecking("manchester", List.of(edit(":A rdfs:label \"Pizza\" ."))));
    }

    @org.junit.jupiter.api.Test
    void returnsFalseForNoEdits() {
        assertFalse(AssistantConsistencyCheckGate.worthChecking("turtle", List.of()));
    }

    @org.junit.jupiter.api.Test
    void skipsAPrefixOnlyEditEvenInAnUnrecognizedFormatLikeOwlxml() {
        EditInput prefixEdit = new EditInput("owlxml", new EditRange(0, 0), "",
                "@prefix custom: <http://www.example.org/custom#> .");
        assertFalse(AssistantConsistencyCheckGate.worthChecking("owlxml", List.of(prefixEdit)));
    }

    @org.junit.jupiter.api.Test
    void skipsAnXmlNamespaceOnlyEditInAnUnrecognizedFormat() {
        EditInput namespaceEdit = new EditInput("owlxml", new EditRange(0, 0), "",
                "xmlns:custom=\"http://www.example.org/custom#\"");
        assertFalse(AssistantConsistencyCheckGate.worthChecking("owlxml", List.of(namespaceEdit)));
    }

    @org.junit.jupiter.api.Test
    void stillFlagsRealContentInAnUnrecognizedFormat() {
        EditInput realEdit = new EditInput("owlxml", new EditRange(0, 1), "",
                "<SubClassOf><Class IRI=\"#A\"/><Class IRI=\"#B\"/></SubClassOf>");
        assertTrue(AssistantConsistencyCheckGate.worthChecking("owlxml", List.of(realEdit)));
    }
}
