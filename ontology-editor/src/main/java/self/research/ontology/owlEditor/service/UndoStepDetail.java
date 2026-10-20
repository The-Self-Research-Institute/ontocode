package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.List;
import java.util.Locale;

final class UndoStepDetail {

    record Detail(String source, String affected, String restoring) {}

    private static final String[] VERBS = {"add", "remove", "create", "delete", "update", "change", "set", "modify", "move", "rename"};

    private static final java.util.Set<String> KINDS = java.util.Set.of(
            "class", "individual", "property", "object property", "data property", "annotation property", "entity");

    private UndoStepDetail() {}

    static Detail of(List<HistoryChange> group) {
        HistoryChange head = group.get(0);
        if (group.size() > 1) {
            return new Detail(source(head), group.size() + " changes", null);
        }
        return new Detail(source(head), affected(head), restoring(head));
    }

    private static String source(HistoryChange entry) {
        if ("AI".equalsIgnoreCase(entry.getSource()) || entry.getAi() != null) {
            return "AI assistant";
        }
        String operation = entry.getOperationType() == null ? "" : entry.getOperationType().toLowerCase(Locale.ROOT);
        if (operation.startsWith("codeview")) {
            return "Code View";
        }
        String area = area(entry.getEntityType());
        return area == null ? null : "Entities → " + area;
    }

    private static String area(String entityType) {
        if (entityType == null) {
            return null;
        }
        return switch (entityType.toUpperCase(Locale.ROOT)) {
            case "CLASS" -> "Classes";
            case "PROPERTY", "OBJECT_PROPERTY", "DATA_PROPERTY" -> "Properties";
            case "INDIVIDUAL" -> "Individuals";
            case "ANNOTATION" -> "Annotations";
            default -> null;
        };
    }

    private static String affected(HistoryChange entry) {
        String name = (entry.getEntityLabel() == null || entry.getEntityLabel().isBlank()) ? localName(entry.getEntityIRI()) : entry.getEntityLabel();
        if (name == null) {
            return null;
        }
        String aspect = aspect(entry.getOperationType());
        if (aspect != null && KINDS.contains(aspect.toLowerCase(Locale.ROOT))) {
            aspect = null;
        }
        return aspect == null ? name : name + " → " + aspect;
    }

    private static String aspect(String operation) {
        if (operation == null) {
            return null;
        }
        String rest = operation;
        for (String verb : VERBS) {
            if (rest.toLowerCase(Locale.ROOT).startsWith(verb)) {
                rest = rest.substring(verb.length());
                break;
            }
        }
        rest = rest.replaceAll("([a-z0-9])([A-Z])", "$1 $2").trim();
        if (rest.isEmpty() || rest.length() == operation.length()) {
            return null;
        }
        String lower = rest.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String restoring(HistoryChange entry) {
        String from = localName(entry.getNewValue());
        String to = localName(entry.getOldValue());
        return from == null || to == null || from.equals(to) ? null : from + " → " + to;
    }

    private static String localName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.startsWith("urn:") || text.length() > 80) {
            return text.startsWith("urn:") ? null : text.substring(0, 77) + "...";
        }
        int cut = Math.max(text.lastIndexOf('#'), text.lastIndexOf('/'));
        String name = cut >= 0 && text.contains(":") ? text.substring(cut + 1) : text;
        return name.isBlank() ? null : name;
    }
}
