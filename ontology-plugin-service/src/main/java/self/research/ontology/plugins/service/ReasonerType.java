package self.research.ontology.plugins.service;

import java.util.Locale;

/**
 * Supported reasoner types.
 * All reasoners are fully functional and available.
 */
public enum ReasonerType {
    HERMIT("HermiT"),
    FACTPLUSPLUS("FaCT++"),
    ELK("ELK"), // Temporarily disabled
    STRUCTURAL("Structural");

    private final String displayName;

    ReasonerType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static ReasonerType parse(String raw) {
        String name = raw.trim().toUpperCase(Locale.ROOT);
        if (name.equals("OPENLLET") || name.equals("PELLET")) {
            return HERMIT;
        }
        return valueOf(name);
    }
}
