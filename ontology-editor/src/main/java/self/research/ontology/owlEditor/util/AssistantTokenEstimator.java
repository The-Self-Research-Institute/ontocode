package self.research.ontology.owlEditor.util;

public final class AssistantTokenEstimator {

    private AssistantTokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        return Math.max(1, text.length() / 4);
    }
}
