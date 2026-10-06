package self.research.ontology.swrl.model;

public class InferredAxiom {
    private String axiomType;
    private String description;
    private String readable;
    private String subjectIri;
    private String predicateIri;
    private String objectIri;
    private String objectLiteral;
    private String literalDatatypeIri;
    private String literalLangTag;

    public InferredAxiom() {}

    public InferredAxiom(String axiomType, String description, String readable) {
        this.axiomType = axiomType;
        this.description = description;
        this.readable = readable;
    }

    public InferredAxiom(String axiomType, String description, String readable,
                          String subjectIri, String predicateIri, String objectIri,
                          String objectLiteral, String literalDatatypeIri, String literalLangTag) {
        this.axiomType = axiomType;
        this.description = description;
        this.readable = readable;
        this.subjectIri = subjectIri;
        this.predicateIri = predicateIri;
        this.objectIri = objectIri;
        this.objectLiteral = objectLiteral;
        this.literalDatatypeIri = literalDatatypeIri;
        this.literalLangTag = literalLangTag;
    }

    public String getAxiomType() { return axiomType; }
    public void setAxiomType(String axiomType) { this.axiomType = axiomType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getReadable() { return readable; }
    public void setReadable(String readable) { this.readable = readable; }

    public String getSubjectIri() { return subjectIri; }
    public void setSubjectIri(String subjectIri) { this.subjectIri = subjectIri; }

    public String getPredicateIri() { return predicateIri; }
    public void setPredicateIri(String predicateIri) { this.predicateIri = predicateIri; }

    public String getObjectIri() { return objectIri; }
    public void setObjectIri(String objectIri) { this.objectIri = objectIri; }

    public String getObjectLiteral() { return objectLiteral; }
    public void setObjectLiteral(String objectLiteral) { this.objectLiteral = objectLiteral; }

    public String getLiteralDatatypeIri() { return literalDatatypeIri; }
    public void setLiteralDatatypeIri(String literalDatatypeIri) { this.literalDatatypeIri = literalDatatypeIri; }

    public String getLiteralLangTag() { return literalLangTag; }
    public void setLiteralLangTag(String literalLangTag) { this.literalLangTag = literalLangTag; }
}