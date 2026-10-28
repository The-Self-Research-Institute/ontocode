package self.research.ontology.owlEditor.service.infer;

final class Deadline {

    private final long endNanos;

    Deadline(long millis) {
        this.endNanos = System.nanoTime() + millis * 1_000_000L;
    }

    long remainingMillis() {
        return Math.max(0L, (endNanos - System.nanoTime()) / 1_000_000L);
    }

    boolean expired() {
        return System.nanoTime() >= endNanos;
    }

    void check() {
        if (expired()) {
            throw new InferenceException(InferenceException.TIMEOUT,
                    "Computing the inferred view took longer than the time limit. Try again or narrow the ontology.");
        }
    }
}
