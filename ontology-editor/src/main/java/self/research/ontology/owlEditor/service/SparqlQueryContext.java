package self.research.ontology.owlEditor.service;

/**
 * Thread-local read scope for per-user draft named graphs.
 * Set by {@link self.research.ontology.owlEditor.config.SparqlQueryContextInterceptor}.
 */
public final class SparqlQueryContext {

    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    private SparqlQueryContext() {
    }

    public static void setUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            USER_ID.remove();
        } else {
            USER_ID.set(userId);
        }
    }

    public static String getUserId() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}
