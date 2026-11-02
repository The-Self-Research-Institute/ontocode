package self.research.ontology.common.admission;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

public final class Reservation implements AutoCloseable {

    public enum State { ACTIVE, RELEASED }

    private final long id;
    private final String owner;
    private final String kind;
    private final long bytes;
    private final Instant createdAt;
    private final long parentId;
    private final ReservationLedger ledger;
    private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);

    Reservation(long id, String owner, String kind, long bytes, Instant createdAt, long parentId,
            ReservationLedger ledger) {
        this.id = id;
        this.owner = owner;
        this.kind = kind;
        this.bytes = bytes;
        this.createdAt = createdAt;
        this.parentId = parentId;
        this.ledger = ledger;
    }

    public long id() {
        return id;
    }

    public String owner() {
        return owner;
    }

    public String kind() {
        return kind;
    }

    public long bytes() {
        return bytes;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long parentId() {
        return parentId;
    }

    public State state() {
        return state.get();
    }

    public boolean isActive() {
        return state.get() == State.ACTIVE;
    }

    public boolean release() {
        if (state.compareAndSet(State.ACTIVE, State.RELEASED)) {
            ledger.onRelease(this);
            return true;
        }
        ledger.onDoubleRelease(this);
        return false;
    }

    @Override
    public void close() {
        release();
    }

    @Override
    public String toString() {
        return "Reservation[id=" + id + " owner=" + owner + " kind=" + kind + " bytes=" + bytes + " state="
                + state.get() + "]";
    }
}
