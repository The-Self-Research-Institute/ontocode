package self.research.ontology.common;

import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.reasoner.Node;

import java.util.Optional;

public final class ReasonerNodes {

    private ReasonerNodes() {
    }

    public static Optional<OWLObjectProperty> namedProperty(Node<OWLObjectPropertyExpression> node) {
        return node.entities()
                .filter(expression -> !expression.isAnonymous())
                .map(OWLObjectPropertyExpression::asOWLObjectProperty)
                .sorted()
                .findFirst();
    }
}
