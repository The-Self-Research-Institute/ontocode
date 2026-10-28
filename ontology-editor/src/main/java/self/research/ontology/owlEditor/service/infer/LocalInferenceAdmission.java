package self.research.ontology.owlEditor.service.infer;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class LocalInferenceAdmission implements InferenceAdmission {

    private final Semaphore slot = new Semaphore(1);
    private final long waitMs;
    private final HeapBudget heap;

    public LocalInferenceAdmission(long waitMs, HeapBudget heap) {
        this.waitMs = waitMs;
        this.heap = heap;
    }

    @Override
    public Permit admit(String userId, long estimatedTriples) {
        try {
            if (!slot.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) {
                throw new InferenceException(InferenceException.BUSY,
                        "Another inferred view is being computed on this server. Try again shortly.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InferenceException(InferenceException.BUSY, "Interrupted while waiting to compute the inferred view.");
        }
        if (heap.isLow()) {
            slot.release();
            throw new InferenceException(InferenceException.BUSY,
                    "The server is short on memory right now, so the inferred view cannot be computed. Try again later.");
        }
        return slot::release;
    }

    @Override
    public boolean isBusy() {
        return slot.availablePermits() == 0 || heap.isLow();
    }
}
