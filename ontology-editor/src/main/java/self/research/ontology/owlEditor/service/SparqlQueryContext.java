package self.research.ontology.owlEditor.service;

public final class SparqlQueryContext {

    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    private static final ThreadLocal<Boolean> WANTS_DRAFT = ThreadLocal.withInitial(() -> false);

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

    public static void setWantsDraft(boolean wantsDraft) {
        WANTS_DRAFT.set(wantsDraft);
    }

    public static boolean wantsDraft() {
        return WANTS_DRAFT.get();
    }

    public static String cacheKeyComponent() {
        return wantsDraft() ? ("draft:" + getUserId()) : "public";
    }

    public static void clear() {
        USER_ID.remove();
        WANTS_DRAFT.remove();
    }

    public static <T> java.util.function.Supplier<T> wrap(java.util.function.Supplier<T> supplier) {
        String capturedUserId = getUserId();
        boolean capturedWantsDraft = wantsDraft();
        return () -> {
            String prevUserId = getUserId();
            boolean prevWantsDraft = wantsDraft();
            setUserId(capturedUserId);
            setWantsDraft(capturedWantsDraft);
            try {
                return supplier.get();
            } finally {
                setUserId(prevUserId);
                setWantsDraft(prevWantsDraft);
            }
        };
    }
}
