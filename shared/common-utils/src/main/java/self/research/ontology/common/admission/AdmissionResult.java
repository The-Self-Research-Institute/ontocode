package self.research.ontology.common.admission;

public record AdmissionResult(Reservation reservation, RefusalReason reason, String message, long retryAfterMillis) {

    public static AdmissionResult admitted(Reservation reservation) {
        return new AdmissionResult(reservation, null, "admitted", 0);
    }

    public static AdmissionResult refused(RefusalReason reason, String message, long retryAfterMillis) {
        return new AdmissionResult(null, reason, message, retryAfterMillis);
    }

    public boolean isAdmitted() {
        return reservation != null;
    }

    public boolean isRefused() {
        return reservation == null;
    }
}
