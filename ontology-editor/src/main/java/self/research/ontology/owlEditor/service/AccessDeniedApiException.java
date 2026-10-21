package self.research.ontology.owlEditor.service;

public class AccessDeniedApiException extends RuntimeException {

    private final transient AccessDenial denial;

    public AccessDeniedApiException(AccessDenial denial) {
        super(denial.message());
        this.denial = denial;
    }

    public AccessDenial denial() {
        return denial;
    }
}
