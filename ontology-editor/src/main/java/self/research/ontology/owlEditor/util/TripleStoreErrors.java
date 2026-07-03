package self.research.ontology.owlEditor.util;

/**
 * Shared detection of "triple store not reachable yet" failures.
 * Desktop starts Fuseki lazily, so controllers map connection-refused to a
 * retryable 503 instead of a terminal error; keeping the check (and the user
 * message) here stops the copies in each controller from drifting apart.
 */
public final class TripleStoreErrors {

    public static final String STORE_STARTING_MESSAGE =
        "Triple store is starting — try again in a moment.";

    private TripleStoreErrors() {
    }

    public static boolean isConnectionRefused(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException) return true;
            String m = t.getMessage();
            if (m != null && m.contains("Connection refused")) return true;
        }
        return false;
    }
}
