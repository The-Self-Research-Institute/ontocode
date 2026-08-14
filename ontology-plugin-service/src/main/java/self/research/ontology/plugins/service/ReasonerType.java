package self.research.ontology.plugins.service;

/**
 * Supported reasoner types.
 * All reasoners are fully functional and available.
 */
public enum ReasonerType {
    HERMIT("HermiT"),
    PELLET("Pellet"),
    OPENLLET("Openllet"),
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
}
