package self.research.ontology.swrl.service;

public class SwrlBusyException extends RuntimeException {

    private final String reason;
    private final int retryAfterSeconds;

    public SwrlBusyException(String reason, int retryAfterSeconds, String message) {
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
