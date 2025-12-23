package self.research.ontology.plugins.service;

/**
 * Supported reasoner types.
 */
public enum ReasonerType {
    HERMIT("HermiT"),
    PELLET("Pellet"),
    OPENLLET("Openllet"),
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
}
