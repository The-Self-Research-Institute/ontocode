package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.common.preview.PreviewStamp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MutationPreconditionGuardTest {

    private final OntologyVersionStamps stamps = mock(OntologyVersionStamps.class);
    private final MutationPreconditionGuard guard = new MutationPreconditionGuard(stamps);

    MutationPreconditionGuardTest() {
        when(stamps.publicRevision("p")).thenReturn(7L);
        when(stamps.draftVersion("p", "u")).thenReturn("3.12");
        when(stamps.rulesFingerprint("p")).thenReturn("abc");
    }

    @Test
    void noPreconditionMeansNoCheck() {
        assertDoesNotThrow(() -> guard.check("p", false, "u", null));
        assertDoesNotThrow(() -> guard.check("p", false, "u", new PreviewStamp(null, null, null)));
    }

    @Test
    void aMatchingPublicStampPasses() {
        assertDoesNotThrow(() -> guard.check("p", false, "u", new PreviewStamp(7L, null, "abc")));
    }

    @Test
    void aChangedOntologyRevisionIsStale() {
        StalePreviewException e = assertThrows(StalePreviewException.class,
                () -> guard.check("p", false, "u", new PreviewStamp(6L, null, "abc")));
        assertEquals(1, e.reasons().size());
        assertEquals("the ontology changed after the preview was made", e.reasons().get(0));
    }

    @Test
    void aChangedDraftIsStaleAndOnlyTheDraftIsLookedAtInDraftMode() {
        assertDoesNotThrow(() -> guard.check("p", true, "u", new PreviewStamp(1L, "3.12", null)));
        StalePreviewException e = assertThrows(StalePreviewException.class,
                () -> guard.check("p", true, "u", new PreviewStamp(null, "3.11", null)));
        assertEquals("your draft changed after the preview was made", e.reasons().get(0));
    }

    @Test
    void changedRulesAreStale() {
        StalePreviewException e = assertThrows(StalePreviewException.class,
                () -> guard.check("p", false, "u", new PreviewStamp(7L, null, "different")));
        assertEquals("the saved rules changed after the preview was made", e.reasons().get(0));
    }

    @Test
    void everyChangedThingIsReported() {
        StalePreviewException e = assertThrows(StalePreviewException.class,
                () -> guard.check("p", false, "u", new PreviewStamp(1L, null, "different")));
        assertEquals(2, e.reasons().size());
    }
}
