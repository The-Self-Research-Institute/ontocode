package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.document.UndoIntentDocument;
import self.research.ontology.owlEditor.document.UndoIntentDocument.Stage;
import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.List;
import java.util.Set;

final class UndoOperationContext {

    private record Holder(UndoIntentService intents, UndoIntentDocument intent, Set<String> blocked) {}

    private static final ThreadLocal<Holder> CURRENT = new ThreadLocal<>();

    private UndoOperationContext() {
    }

    static void start(UndoIntentService intents, UndoIntentDocument intent, Set<String> blocked) {
        CURRENT.set(new Holder(intents, intent, blocked));
    }

    static void end() {
        CURRENT.remove();
    }

    static void stage(Stage stage) {
        Holder holder = CURRENT.get();
        if (holder != null) {
            holder.intents().advance(holder.intent(), stage);
        }
    }

    static void entries(List<HistoryChange> entries, String undoAuditId) {
        Holder holder = CURRENT.get();
        if (holder != null) {
            holder.intents().attach(holder.intent(), entries, undoAuditId);
        }
    }

    static void audit(String auditId) {
        Holder holder = CURRENT.get();
        if (holder != null) {
            holder.intents().attachAudit(holder.intent(), auditId);
        }
    }

    static boolean blocked(String entryId) {
        Holder holder = CURRENT.get();
        return holder != null && entryId != null && holder.blocked().contains(entryId);
    }
}
