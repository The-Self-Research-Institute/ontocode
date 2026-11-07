package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.reasoner.OWLReasoner;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ReasonerLease implements AutoCloseable {

    private final ReasonerService service;
    private final ReasonerService.Entry entry;
    private final OWLReasoner reasoner;
    private final boolean holdsLock;
    private final boolean oneShot;
    private final AtomicBoolean closed = new AtomicBoolean();

    ReasonerLease(ReasonerService service, ReasonerService.Entry entry, OWLReasoner reasoner, boolean holdsLock,
            boolean oneShot) {
        this.service = service;
        this.entry = entry;
        this.reasoner = reasoner;
        this.holdsLock = holdsLock;
        this.oneShot = oneShot;
    }

    public OWLReasoner reasoner() {
        return reasoner;
    }

    public void retire() {
        service.retireEntry(entry);
    }

    public ReasonerLease handOff() {
        entry.borrowAgain();
        return new ReasonerLease(service, entry, reasoner, false, false);
    }

    ReasonerService.Entry entry() {
        return entry;
    }

    boolean holdsLock() {
        return holdsLock;
    }

    boolean oneShot() {
        return oneShot;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            service.releaseLease(this);
        }
    }
}
