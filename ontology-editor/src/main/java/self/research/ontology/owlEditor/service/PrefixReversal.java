package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.List;
import java.util.Optional;

final class PrefixReversal {

    private PrefixReversal() {
    }

    static boolean isPrefixOp(String operationType) {
        return "prefixAdded".equals(operationType) || "prefixModified".equals(operationType)
                || "prefixDeleted".equals(operationType);
    }

    static Optional<RollbackAction> plan(HistoryChange change, boolean undo) {
        String prefix = change.getEntityLabel();
        String oldNamespace = blankToNull(change.getOldValue());
        String newNamespace = blankToNull(change.getNewValue());
        String oldPrefix = blankToNull(change.getAnnotationProperty());
        if (prefix == null || prefix.isBlank()) {
            return Optional.empty();
        }
        boolean renamed = oldPrefix != null && !oldPrefix.equals(prefix);
        return switch (change.getOperationType()) {
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
        return plan(change, undo).map(List::of).orElse(List.of());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
