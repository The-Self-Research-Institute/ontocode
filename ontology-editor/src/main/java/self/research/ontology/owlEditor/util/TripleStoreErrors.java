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
}
