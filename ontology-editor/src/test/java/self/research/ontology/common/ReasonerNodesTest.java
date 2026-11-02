package self.research.ontology.common;

import org.junit.jupiter.api.Test;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReasonerNodesTest {

    private static final String NS = "http://example.org#";

    @Test
    void aSubPropertyWithAnInverseIsStillFoundByItsName() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLObjectProperty hasIngredient = df.getOWLObjectProperty(IRI.create(NS + "hasIngredient"));
        OWLObjectProperty hasBase = df.getOWLObjectProperty(IRI.create(NS + "hasBase"));
        OWLObjectProperty isBaseOf = df.getOWLObjectProperty(IRI.create(NS + "isBaseOf"));
        OWLOntology ontology = manager.createOntology();
        manager.addAxiom(ontology, df.getOWLSubObjectPropertyOfAxiom(hasBase, hasIngredient));
        manager.addAxiom(ontology, df.getOWLInverseObjectPropertiesAxiom(hasBase, isBaseOf));
        OWLReasoner reasoner = new ReasonerFactory().createReasoner(ontology);

        NodeSet<OWLObjectPropertyExpression> children = reasoner.getSubObjectProperties(hasIngredient, true);
        List<OWLObjectProperty> named = new ArrayList<>();
        for (Node<OWLObjectPropertyExpression> node : children) {
            ReasonerNodes.namedProperty(node).ifPresent(named::add);
        }

        assertFalse(named.isEmpty());
        assertEquals(hasBase, named.get(0));
        reasoner.dispose();
    }
}
