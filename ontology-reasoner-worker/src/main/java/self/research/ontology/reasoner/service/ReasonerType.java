package self.research.ontology.reasoner.service;

import org.slf4j.LoggerFactory;

import java.util.Locale;

public enum ReasonerType {
    HERMIT("HermiT"),
    FACTPLUSPLUS("FaCT++"),
    ELK("ELK"),
    STRUCTURAL("Structural");

    private final String displayName;

    ReasonerType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static ReasonerType fromRequest(String raw) {
        if (raw == null || raw.isBlank()) {
            return HERMIT;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT);
        if (name.equals("OPENLLET") || name.equals("PELLET")) {
            return HERMIT;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            LoggerFactory.getLogger(ReasonerType.class)
                    .warn("Unknown reasoner type '{}', falling back to HERMIT", raw);
            return HERMIT;
        }
    }
}
