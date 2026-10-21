package self.research.ontology.common.security;

public final class StrictSecurity {

    public static final String PUBLIC_INTERNAL_TOKEN = "ontocode-internal";

    private StrictSecurity() {
    }

    public static void require(boolean strict, boolean satisfied, String problem) {
        if (strict && !satisfied) {
            throw new IllegalStateException("[SECURITY] " + problem);
        }
    }

    public static boolean isCustomInternalToken(String token) {
        return token != null && !token.isBlank() && !PUBLIC_INTERNAL_TOKEN.equals(token);
    }
}
