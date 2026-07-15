package self.research.ontology.owlEditor.service;

/**
 * Thread-local read scope for per-user draft named graphs.
 * Set by {@link self.research.ontology.owlEditor.config.SparqlQueryContextInterceptor}.
 */
public final class SparqlQueryContext {

    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    // Explicit per-request opt-in for draft-graph scoping. userId alone can't signal this:
    // it's resolved from the JWT/X-Ontocode-User-Id header on EVERY request (needed for
    // attribution, personalization, etc.), including ones made while viewing Public — so
    // "is there a resolvable userId with a ready draft" is true for a drafting user even
    // when they've explicitly switched to Public. Only an explicit `draft=true` request
    // parameter may turn draft-graph scoping on.
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

    /**
     * Cache-key component for @Cacheable query methods: 'public' for every non-draft request
     * (shared across all users — draft scope doesn't apply), or a per-user key while drafting.
     * Using getUserId() alone (without wantsDraft) as the key is wrong on both counts: it
     * fragments the shared public cache per-user for no reason, AND collides a user's draft
     * request with their own public request under the same key, serving stale cross-scope data.
     */
    public static String cacheKeyComponent() {
        return wantsDraft() ? ("draft:" + getUserId()) : "public";
    }

    public static void clear() {
        USER_ID.remove();
        WANTS_DRAFT.remove();
    }

    /**
     * Wraps a supplier so it runs with THIS thread's current userId/wantsDraft, regardless
     * of which thread actually executes it. Needed because these are plain ThreadLocals and
     * the query layer dispatches sub-queries via CompletableFuture.supplyAsync (ForkJoinPool
     * worker threads) — without this, every such worker thread sees no user/draft context and
     * silently scopes to main graph even on a request that explicitly asked for draft=true.
     */
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
