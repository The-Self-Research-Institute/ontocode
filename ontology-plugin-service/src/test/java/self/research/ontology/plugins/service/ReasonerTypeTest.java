package self.research.ontology.plugins.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReasonerTypeTest {

    @Test
    void openlletAndPelletStillMapToHermit() {
        assertEquals(ReasonerType.HERMIT, ReasonerType.parse("Openllet"));
        assertEquals(ReasonerType.HERMIT, ReasonerType.parse(" pellet "));
    }
}
