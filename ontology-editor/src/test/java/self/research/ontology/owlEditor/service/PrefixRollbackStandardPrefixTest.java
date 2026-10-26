package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrefixRollbackStandardPrefixTest {

    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";

    private PrefixRollbackActions actionsWith(List<Map<String, String>> prefixes) {
        OntologyMetadataService metadata = mock(OntologyMetadataService.class);
        when(metadata.getPrefixes("p")).thenReturn(prefixes);
        return new PrefixRollbackActions(metadata);
    }

    @Test
    void aDeletedStandardPrefixCountsAsAbsentEvenThoughThePrefixListAlwaysShowsIt() {
        PrefixRollbackActions actions = actionsWith(List.of(Map.of("prefix", "skos", "namespace", SKOS)));

        assertTrue(actions.holds("p", RollbackAction.setPrefix("skos", SKOS, null, null, true), false, null));
    }

    @Test
    void aStandardPrefixPointingSomewhereElseIsStillInTheWay() {
        PrefixRollbackActions actions = actionsWith(List.of(Map.of("prefix", "skos", "namespace", "http://example.org/other#")));

        assertFalse(actions.holds("p", RollbackAction.setPrefix("skos", SKOS, null, null, true), false, null));
    }

    @Test
    void aCustomPrefixThatStillExistsIsStillInTheWay() {
        PrefixRollbackActions actions = actionsWith(List.of(Map.of("prefix", "mine", "namespace", "http://example.org/mine#")));

        assertFalse(actions.holds("p", RollbackAction.setPrefix("mine", "http://example.org/mine#", null, null, true), false, null));
    }

    @Test
    void undoingAnAddedPrefixThatIsAlreadyGoneIsNotRefused() {
        PrefixRollbackActions actions = actionsWith(List.of());

        assertTrue(actions.holds("p", RollbackAction.deletePrefix("ges", "http://www.w3.org/2001/XMLSchema#"), false, null));
    }

    @Test
    void undoingAnAddedPrefixThatNowPointsElsewhereIsStillRefused() {
        PrefixRollbackActions actions = actionsWith(List.of(Map.of("prefix", "ges", "namespace", "http://example.org/other#")));

        assertFalse(actions.holds("p", RollbackAction.deletePrefix("ges", "http://www.w3.org/2001/XMLSchema#"), false, null));
    }

    @Test
    void aCustomPrefixThatIsGoneCanBeRestored() {
        PrefixRollbackActions actions = actionsWith(List.of());

        assertTrue(actions.holds("p", RollbackAction.setPrefix("mine", "http://example.org/mine#", null, null, true), false, null));
    }
}
