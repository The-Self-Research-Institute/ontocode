package self.research.ontology.swrl.service;

public class OntologyTooLargeException extends RuntimeException {

    private final long limitAxioms;

    public OntologyTooLargeException(long limitAxioms) {
        super("This ontology is too large for the rule engine on this server (the limit is about "
                + String.format(java.util.Locale.ROOT, "%,d", limitAxioms)
                + " axioms). Split it into smaller ontologies or ask an administrator to give the rule engine more memory.");
        this.limitAxioms = limitAxioms;
    }

    public long getLimitAxioms() {
        return limitAxioms;
    }
}
