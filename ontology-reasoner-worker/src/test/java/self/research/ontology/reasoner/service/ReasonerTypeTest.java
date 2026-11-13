package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReasonerTypeTest {

    @Test
    void openlletAndPelletStillMapToHermit() {
        assertEquals(ReasonerType.HERMIT, ReasonerType.fromRequest("Openllet"));
        assertEquals(ReasonerType.HERMIT, ReasonerType.fromRequest(" pellet "));
    }

    @Test
    void factPlusPlusIdIsStable() {
        assertEquals(ReasonerType.FACTPLUSPLUS, ReasonerType.fromRequest("factplusplus"));
    }
}
