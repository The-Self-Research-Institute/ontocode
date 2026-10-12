package self.research.ontology.owlEditor.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssistantTokenEstimatorTest {

    @Test
    void emptyOrNullTextEstimatesAsOneToken() {
        assertEquals(1, AssistantTokenEstimator.estimate(null));
        assertEquals(1, AssistantTokenEstimator.estimate(""));
    }

    @Test
    void shortTextEstimatesAsAtLeastOneToken() {
        assertEquals(1, AssistantTokenEstimator.estimate("hi"));
    }

    @Test
    void estimatesRoughlyCharsDividedByFour() {
        String text = "a".repeat(400);
        assertEquals(100, AssistantTokenEstimator.estimate(text));
    }
}
