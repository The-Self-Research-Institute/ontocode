package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.util.Locale;

final class HistoryClassification {

    private HistoryClassification() {
    }

    static String entityType(String operationType) {
        if (operationType == null || operationType.isBlank()) {
            return "OTHER";
        }
        String upper = operationType.toUpperCase(Locale.ROOT);
        if (upper.startsWith("PREFIX")) {
            return "PREFIX";
        }
        if (upper.contains("RENAME")) {
            return "RENAME";
        }
        if (upper.contains("IMPORT")) {
            return "IMPORT";
        }
        if (upper.contains("SWRL") || upper.contains("RULE") || upper.contains("FUZZY")) {
            return "RULE";
        }
        if (upper.contains("CITATION")) {
            return "CITATION";
        }
        if (upper.contains("DATATYPE")) {
            return "DATATYPE";
        }
        if (upper.contains("ASSERTION") || upper.contains("INDIVIDUAL")) {
            return "INDIVIDUAL";
        }
        if (upper.contains("CLASS")) {
            return "CLASS";
        }
        if (upper.contains("PROPERTY")) {
            return "PROPERTY";
        }
        if (upper.contains("ANNOTATION")) {
            return "ANNOTATION";
        }
        return upper.contains("AXIOM") ? "AXIOM" : "OTHER";
    }

    static String oldValue(MutationOp op) {
        if (updates(op.type())) {
            return firstPresent(op.oldValue(), op.value());
        }
        return removes(op.type()) ? firstPresent(op.oldValue(), op.value(), op.target(), op.classIri()) : op.oldValue();
    }

    static String newValue(MutationOp op) {
        if (updates(op.type())) {
            return firstPresent(op.target(), op.classIri(), op.value());
        }
        return adds(op.type()) ? firstPresent(op.value(), op.target(), op.classIri()) : op.value();
    }

    private static boolean adds(String type) {
        return type != null && (type.startsWith("add") || type.startsWith("create"));
    }

    private static boolean removes(String type) {
        return type != null && (type.startsWith("delete") || type.startsWith("remove"));
    }

    private static boolean updates(String type) {
        return type != null && type.startsWith("update");
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
