package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class KeepBothPizzaFixture {

    static final String P = "http://ex.org/pizza#";
    static final String OBO = "http://purl.obolibrary.org/obo/";
    static final String SUFFIX = "_imported";
    static final String ENTITY = OBO + "BFO_0000001";

    private static final String SOURCE = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix p: <http://ex.org/pizza#> .
            @prefix obo: <http://purl.obolibrary.org/obo/> .

            <http://ex.org/source> a owl:Ontology .
            p:definition a owl:AnnotationProperty .
            p:Percent a rdfs:Datatype .
            p:Pizza a owl:Class ; rdfs:label "Pizza" .
            p:PizzaCheese a owl:Class ; rdfs:label "Pizza cheese"@en ; rdfs:subClassOf p:Pizza .
            p:Cheese a owl:Class ; rdfs:label "Cheese" ; p:definition "A dairy product" .
            p:PizzaTopping a owl:Class ; rdfs:label "Pizza topping" ; rdfs:subClassOf obo:BFO_0000001 .
            p:Mozzarella a owl:Class ; rdfs:subClassOf p:Mystery .
            p:hasTopping a owl:ObjectProperty ; rdfs:label "has topping" ; rdfs:domain p:Pizza .
            p:hasCalories a owl:DatatypeProperty ; rdfs:range p:Percent .
            p:Margherita a owl:NamedIndividual , p:Pizza .
            """;

    private KeepBothPizzaFixture() {
    }

    static void writeSource(Path path) throws Exception {
        Files.writeString(path, SOURCE);
    }

    static void writeTarget(Path path) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://ex.org/target"));
        OWLClass pizza = df.getOWLClass(IRI.create(P + "Pizza"));
        OWLClass pizzaCheese = df.getOWLClass(IRI.create(P + "PizzaCheese"));
        OWLClass cheese = df.getOWLClass(IRI.create(P + "Cheese"));
        OWLClass entity = df.getOWLClass(IRI.create(ENTITY));
        OWLClass mystery = df.getOWLClass(IRI.create(P + "Mystery"));
        OWLClass own = df.getOWLClass(IRI.create(P + "TargetOnly"));
        OWLObjectProperty hasTopping = df.getOWLObjectProperty(IRI.create(P + "hasTopping"));
        OWLDataProperty hasCalories = df.getOWLDataProperty(IRI.create(P + "hasCalories"));
        OWLDatatype percent = df.getOWLDatatype(IRI.create(P + "Percent"));
        OWLAnnotationProperty definition = df.getOWLAnnotationProperty(IRI.create(P + "definition"));
        OWLNamedIndividual margherita = df.getOWLNamedIndividual(IRI.create(P + "Margherita"));
        for (OWLEntity declared : List.of(pizza, pizzaCheese, cheese, entity, mystery, own, hasTopping, hasCalories,
                percent, definition, margherita)) {
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(declared));
        }
        label(manager, ontology, pizza, "Pizza");
        label(manager, ontology, pizzaCheese, "Cheese");
        label(manager, ontology, cheese, "Cheese");
        label(manager, ontology, entity, "entity");
        label(manager, ontology, own, "Target only");
        label(manager, ontology, hasTopping, "has topping");
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(definition, cheese.getIRI(),
                df.getOWLLiteral("A dairy product")));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(pizzaCheese, pizza));
        manager.addAxiom(ontology, df.getOWLObjectPropertyDomainAxiom(hasTopping, pizza));
        manager.addAxiom(ontology, df.getOWLDataPropertyRangeAxiom(hasCalories, percent));
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(pizza, margherita));
        try (FileOutputStream out = new FileOutputStream(path.toFile())) {
            manager.saveOntology(ontology, new RDFXMLDocumentFormat(), out);
        }
    }

    private static void label(OWLOntologyManager manager, OWLOntology ontology, OWLEntity entity, String text) {
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), entity.getIRI(),
                df.getOWLLiteral(text)));
    }
}
