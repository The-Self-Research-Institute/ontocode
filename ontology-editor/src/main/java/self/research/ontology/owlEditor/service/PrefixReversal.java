package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.List;
import java.util.Optional;

final class PrefixReversal {

    private PrefixReversal() {
    }

    static final String AGGREGATE = "prefixesChanged";

    static boolean isPrefixOp(String operationType) {
        return "prefixAdded".equals(operationType) || "prefixModified".equals(operationType)
                || "prefixDeleted".equals(operationType) || AGGREGATE.equals(operationType);
    }

    static boolean isAggregate(HistoryChange change) {
        return AGGREGATE.equals(change.getOperationType());
    }

    static Optional<RollbackAction> plan(HistoryChange change, boolean undo) {
        return plan(change.getOperationType(), change.getEntityLabel(), change.getOldValue(), change.getNewValue(),
                change.getAnnotationProperty(), undo);
    }

    private static Optional<RollbackAction> plan(String operationType, String prefix, String oldValue,
                                                 String newValue, String oldPrefixValue, boolean undo) {
        String oldNamespace = blankToNull(oldValue);
        String newNamespace = blankToNull(newValue);
        String oldPrefix = blankToNull(oldPrefixValue);
        if (prefix == null || prefix.isBlank()) {
            return Optional.empty();
        }
        boolean renamed = oldPrefix != null && !oldPrefix.equals(prefix);
        return switch (operationType == null ? "" : operationType) {
            case "prefixAdded" -> undo
                    ? Optional.of(RollbackAction.deletePrefix(prefix, newNamespace))
                    : newNamespace == null ? Optional.empty()
                    : Optional.of(RollbackAction.setPrefix(prefix, newNamespace, null, null, true));
            case "prefixDeleted" -> undo
                    ? oldNamespace == null ? Optional.empty()
                    : Optional.of(RollbackAction.setPrefix(prefix, oldNamespace, null, null, true))
                    : Optional.of(RollbackAction.deletePrefix(prefix, oldNamespace));
            case "prefixModified" -> modified(prefix, oldPrefix, renamed, oldNamespace, newNamespace, undo);
            default -> Optional.empty();
        };
    }

    private static Optional<RollbackAction> modified(String prefix, String oldPrefix, boolean renamed,
                                                     String oldNamespace, String newNamespace, boolean undo) {
        if (oldNamespace == null || newNamespace == null) {
            return Optional.empty();
        }
        if (undo) {
            return renamed
                    ? Optional.of(RollbackAction.setPrefix(oldPrefix, oldNamespace, prefix, newNamespace, false))
                    : Optional.of(RollbackAction.setPrefix(prefix, oldNamespace, null, newNamespace, false));
        }
        return renamed
                ? Optional.of(RollbackAction.setPrefix(prefix, newNamespace, oldPrefix, oldNamespace, false))
                : Optional.of(RollbackAction.setPrefix(prefix, newNamespace, null, oldNamespace, false));
    }

    static List<RollbackAction> actions(HistoryChange change, boolean undo) {
        if (!isAggregate(change)) {
            return plan(change, undo).map(List::of).orElse(List.of());
        }
        List<HistoryChange.SubChange> parts = change.getSubChanges() == null ? List.of() : change.getSubChanges();
        List<RollbackAction> actions = new java.util.ArrayList<>();
        for (HistoryChange.SubChange part : parts) {
            Optional<RollbackAction> action = plan(part.getAnnotationProperty(), part.getPredicate(), part.getOldValue(),
                    part.getNewValue(), null, undo);
            if (action.isEmpty()) {
                return List.of();
            }
            actions.add(action.get());
        }
        if (undo) {
            java.util.Collections.reverse(actions);
        }
        return actions;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
