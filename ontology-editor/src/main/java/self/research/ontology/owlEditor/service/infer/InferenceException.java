package self.research.ontology.owlEditor.service.infer;

public class InferenceException extends RuntimeException {

    public static final String NONE = "none";
    public static final String STALE = "stale";
    public static final String TOO_LARGE = "too_large";
    public static final String TIMEOUT = "timeout";
    public static final String BUSY = "busy";
    public static final String CHANGED = "changed_during_capture";

    private final String reason;
    private final java.time.Instant computedAt;

    public InferenceException(String reason, String message) {
        this(reason, message, null);
    }

    public InferenceException(String reason, String message, java.time.Instant computedAt) {
        super(message);
        this.reason = reason;
        this.computedAt = computedAt;
    }

    public java.time.Instant computedAt() {
        return computedAt;
    }

    public String reason() {
        return reason;
    }
}
