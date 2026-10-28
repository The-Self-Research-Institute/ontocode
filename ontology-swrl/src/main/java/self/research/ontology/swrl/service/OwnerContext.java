package self.research.ontology.swrl.service;

public final class OwnerContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private OwnerContext() {
    }

    public static void set(String owner) {
        if (owner == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(owner);
        }
    }

    public static String current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
