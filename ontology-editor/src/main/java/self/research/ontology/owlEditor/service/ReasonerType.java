package self.research.ontology.owlEditor.service;

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

    public static ReasonerType parse(String raw) {
        String name = raw.trim().toUpperCase(Locale.ROOT);
        if (name.equals("OPENLLET") || name.equals("PELLET")) {
            LoggerFactory.getLogger(ReasonerType.class)
                    .warn("Openllet and Pellet are no longer available; using HermiT");
            return HERMIT;
        }
        return valueOf(name);
    }
}
