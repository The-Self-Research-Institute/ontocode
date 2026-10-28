package self.research.ontology.owlEditor.service;

public class ImportQueueFullException extends RuntimeException {

    private final String reason;
    private final int retryAfterSeconds;

    public ImportQueueFullException(String reason, int retryAfterSeconds, String message) {
        super(message);
        this.reason = reason;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getReason() {
        return reason;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
