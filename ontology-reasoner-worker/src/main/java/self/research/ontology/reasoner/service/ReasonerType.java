package self.research.ontology.reasoner.service;

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
