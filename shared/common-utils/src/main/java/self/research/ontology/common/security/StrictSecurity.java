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

    public static final String PUBLIC_TOKEN_MESSAGE = "Startup stopped because the internal token is still the public default. Set ONTOCODE_INTERNAL_TOKEN to a long random value and restart.";

    public static void requireCustomInternalToken(boolean strict, String token) {
        require(strict, isCustomInternalToken(token), PUBLIC_TOKEN_MESSAGE);
    }

    public static boolean isCustomInternalToken(String token) {
        return token != null && !token.isBlank() && !PUBLIC_INTERNAL_TOKEN.equals(token);
    }
}
