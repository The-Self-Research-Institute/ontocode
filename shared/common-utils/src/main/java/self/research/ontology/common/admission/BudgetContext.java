package self.research.ontology.common.admission;

import java.util.Optional;
import java.util.concurrent.Callable;

public final class BudgetContext {

    private static final ThreadLocal<Reservation> CURRENT = new ThreadLocal<>();

    private BudgetContext() {}

    public static Optional<Reservation> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    static Reservation activeHolder() {
        Reservation held = CURRENT.get();
        return held != null && held.isActive() ? held : null;
    }

    public static boolean holdsReservation() {
        return activeHolder() != null;
    }

    public static Scope enter(Reservation reservation) {
        Reservation previous = CURRENT.get();
        if (reservation == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(reservation);
        }
        return new Scope(previous);
    }

    public static Runnable wrap(Runnable task) {
        Reservation captured = CURRENT.get();
        return () -> {
            try (Scope ignored = enter(captured)) {
                task.run();
            }
        };
    }

    public static <T> Callable<T> wrap(Callable<T> task) {
        Reservation captured = CURRENT.get();
        return () -> {
            try (Scope ignored = enter(captured)) {
                return task.call();
            }
        };
    }

    public static final class Scope implements AutoCloseable {
        private final Reservation previous;
        private boolean closed;

        private Scope(Reservation previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
