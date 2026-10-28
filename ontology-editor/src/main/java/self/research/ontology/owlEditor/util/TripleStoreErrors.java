package self.research.ontology.owlEditor.util;

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

    public static final String QUERY_TIMEOUT_MESSAGE =
        "The query took longer than the server allows (2 minutes). Narrow it, add a filter or a LIMIT.";

    public static boolean isQueryTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.util.concurrent.TimeoutException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof org.eclipse.rdf4j.query.QueryInterruptedException) {
                return true;
            }
            String m = t.getMessage();
            if (m != null) {
                String lower = m.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("timed out") || lower.contains("exceeded specified timeout")) return true;
            }
        }
        return false;
    }
}
