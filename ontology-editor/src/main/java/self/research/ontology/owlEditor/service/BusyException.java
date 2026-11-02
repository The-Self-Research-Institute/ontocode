package self.research.ontology.owlEditor.service;

import self.research.ontology.common.admission.AdmissionResult;
import self.research.ontology.common.admission.RefusalReason;

public class BusyException extends RuntimeException {

    private final RefusalReason reason;
    private final long retryAfterMillis;

    public BusyException(RefusalReason reason, String message, long retryAfterMillis) {
        super(message);
        this.reason = reason;
        this.retryAfterMillis = retryAfterMillis;
    }

    public BusyException(AdmissionResult refusal) {
        this(refusal.reason(), refusal.message(), refusal.retryAfterMillis());
    }

    public RefusalReason reason() {
        return reason;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    public long retryAfterSeconds() {
        return retryAfterMillis <= 0 ? 0 : Math.max(1, (retryAfterMillis + 999) / 1000);
    }
}
