package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.semanticweb.owlapi.model.HasIRI;
import org.semanticweb.owlapi.model.OWLLiteral;

final class RdfTerms {

    static final ValueFactory VF = SimpleValueFactory.getInstance();
    static final IRI TYPE = RDF.TYPE;
    static final IRI SUB_CLASS = RDFS.SUBCLASSOF;
    static final IRI SUB_PROPERTY = RDFS.SUBPROPERTYOF;
    static final IRI EQUIVALENT_CLASS = OWL.EQUIVALENTCLASS;
    static final IRI EQUIVALENT_PROPERTY = OWL.EQUIVALENTPROPERTY;
    static final IRI SAME_AS = OWL.SAMEAS;
    static final IRI DIFFERENT_FROM = OWL.DIFFERENTFROM;
    static final IRI THING = OWL.THING;
    static final IRI NOTHING = OWL.NOTHING;

    private RdfTerms() {
    }

    static IRI iri(HasIRI entity) {
        return VF.createIRI(entity.getIRI().toString());
    }

    static Statement triple(IRI s, IRI p, Value o) {
        return VF.createStatement(s, p, o);
    }

    static Value literal(OWLLiteral literal) {
        if (literal.hasLang()) {
            return VF.createLiteral(literal.getLiteral(), literal.getLang());
        }
        String datatype = literal.getDatatype().getIRI().toString();
        if ("http://www.w3.org/1999/02/22-rdf-syntax-ns#PlainLiteral".equals(datatype)) {
            return VF.createLiteral(literal.getLiteral(), XSD.STRING);
        }
        return VF.createLiteral(literal.getLiteral(), VF.createIRI(datatype));
    }
}
