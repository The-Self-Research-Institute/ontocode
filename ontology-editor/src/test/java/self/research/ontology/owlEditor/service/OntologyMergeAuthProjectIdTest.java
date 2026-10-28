package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OntologyMergeAuthProjectIdTest {

    @Test
    void theAuthServiceGetsOnlyThePartBeforeTheFileId() {
        assertEquals("proj-f6a7dc47", OntologyMergeService.authProjectId("proj-f6a7dc47--c9689820-36b2-4595-ac28-9bb75e11df55"));
    }

    @Test
    void aPlainProjectIdIsUnchanged() {
        assertEquals("proj-f6a7dc47", OntologyMergeService.authProjectId("proj-f6a7dc47"));
    }
}
