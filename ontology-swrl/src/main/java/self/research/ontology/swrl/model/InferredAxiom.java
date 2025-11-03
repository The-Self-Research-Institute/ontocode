package self.research.ontology.swrl.model;

public class InferredAxiom {
    private String axiomType;
    private String description;
    private String readable;

    public InferredAxiom() {}

    public InferredAxiom(String axiomType, String description, String readable) {
        this.axiomType = axiomType;
        this.description = description;
        this.readable = readable;
    }

    public String getAxiomType() { return axiomType; }
    public void setAxiomType(String axiomType) { this.axiomType = axiomType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getReadable() { return readable; }
    public void setReadable(String readable) { this.readable = readable; }
}