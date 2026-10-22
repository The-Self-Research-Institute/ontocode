package self.research.ontology.owlEditor.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

public final class DraftRecordScope {

    private static final ThreadLocal<DraftRecordScope> CURRENT = new ThreadLocal<>();

    private final Set<String> changeSetIds = new LinkedHashSet<>();
    private boolean observed;

    private DraftRecordScope() {
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    public static void observed(String changeSetId, boolean changed) {
        DraftRecordScope scope = CURRENT.get();
        if (scope == null) {
            return;
        }
        scope.observed = true;
        if (changed && changeSetId != null) {
            scope.changeSetIds.add(changeSetId);
        }
    }

    public static void link(String changeSetId) {
        observed(changeSetId, true);
    }

    public static <T> Outcome<T> collect(Callable<T> work) throws Exception {
        DraftRecordScope previous = CURRENT.get();
        DraftRecordScope scope = new DraftRecordScope();
        CURRENT.set(scope);
        T value;
        try {
            value = work.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
                previous.changeSetIds.addAll(scope.changeSetIds);
                previous.observed |= scope.observed;
            }
        }
        return new Outcome<>(value, List.copyOf(scope.changeSetIds), scope.observed);
    }

    public record Outcome<T>(T value, List<String> changeSetIds, boolean observed) {

        public boolean changed() {
            return !observed || !changeSetIds.isEmpty();
        }
    }
}
