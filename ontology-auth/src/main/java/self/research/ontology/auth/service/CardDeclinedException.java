package self.research.ontology.auth.service;

public class CardDeclinedException extends IllegalStateException {

    public static final String MESSAGE = "Your card was declined. Use a different card and try again.";

    public CardDeclinedException() {
        super(MESSAGE);
    }
}
