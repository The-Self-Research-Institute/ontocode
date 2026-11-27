package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopClassDetailsAncestorsTest {

    private static final String NS = "http://example.org/t#";

    private static final String ONTOLOGY = """
            Prefix(:=<http://example.org/t#>)
            Prefix(owl:=<http://www.w3.org/2002/07/owl#>)
            Prefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)
            Ontology(<http://example.org/t>
            Declaration(Class(:Leaf))
            Declaration(Class(:Parent))
            Declaration(Class(:Grand))
            Declaration(Class(:Great))
            Declaration(Class(:Filler))
            Declaration(ObjectProperty(:hasPart))
            Declaration(ObjectProperty(:partOf))
            Declaration(DataProperty(:age))
            SubClassOf(:Leaf :Parent)
            SubClassOf(:Leaf ObjectSomeValuesFrom(:hasPart :Filler))
            EquivalentClasses(:Leaf DataSomeValuesFrom(:age DatatypeRestriction(xsd:integer xsd:minInclusive "18"^^xsd:integer)))
            SubClassOf(:Parent :Grand)
            SubClassOf(:Grand :Great)
            SubClassOf(:Grand ObjectSomeValuesFrom(:hasPart :Filler))
            EquivalentClasses(:Great ObjectSomeValuesFrom(:partOf :Filler))
            )
            """;

    private Map<String, Object> details() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ont = manager.loadOntologyFromOntologyDocument(new org.semanticweb.owlapi.io.StringDocumentSource(ONTOLOGY));
        return new DesktopHierarchyService().buildClassDetails(ont, null, NS + "Leaf");
    }

    private List<Map<String, String>> ancestors() throws Exception {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> list = (List<Map<String, String>>) details().get("anonymousAncestorAxioms");
        return list;
    }

    private static String describe(Map<String, String> e) {
        return e.get("ancestorIri").replace(NS, "") + " -> " + e.get("definition") + " [navigable=" + e.get("navigable") + "]";
    }

    @Test
    void listsNamedAncestorsBeyondTheDirectParent() throws Exception {
        List<Map<String, String>> list = ancestors();
        Set<String> named = list.stream()
                .filter(e -> "true".equals(e.get("navigable")))
                .map(e -> e.get("ancestorIri").replace(NS, "") + ">" + e.get("id").replace(NS, ""))
                .collect(Collectors.toSet());
        assertEquals(Set.of("Parent>Grand", "Grand>Great"), named, () -> list.stream().map(DesktopClassDetailsAncestorsTest::describe).toList().toString());
    }

    @Test
    void keepsRestrictionAncestors() throws Exception {
        List<Map<String, String>> list = ancestors();
        assertTrue(list.stream().anyMatch(e -> "false".equals(e.get("navigable")) && e.get("ancestorIri").endsWith("#Grand")
                && e.get("definition").contains("hasPart")), list.toString());
    }

    @Test
    void listsAnonymousEquivalentClassOfAnAncestor() throws Exception {
        List<Map<String, String>> list = ancestors();
        assertTrue(list.stream().anyMatch(e -> "false".equals(e.get("navigable")) && e.get("ancestorIri").endsWith("#Great")
                && e.get("definition").contains("partOf")), list.toString());
    }

    @Test
    void doesNotListTheClassOwnDirectParentOnItsOwn() throws Exception {
        assertTrue(ancestors().stream().noneMatch(e -> e.get("ancestorIri").endsWith("#Leaf") && "true".equals(e.get("navigable"))));
    }

    @Test
    void doesNotListTheClassOwnRestrictionAsInherited() throws Exception {
        assertTrue(ancestors().stream().noneMatch(e -> (NS + "Leaf").equals(e.get("ancestorIri"))));
    }

    @Test
    void rendersDatatypeRestrictionInsteadOfItsImplementationName() throws Exception {
        var axioms = (List<?>) details().get("equivalentClassesAxioms");
        assertTrue(axioms.stream().map(axiom -> ((Map<?, ?>) axiom).get("definition").toString())
                .anyMatch(text -> text.contains("age some") && text.contains("integer") && text.contains(">= 18")), axioms.toString());
    }

    @Test
    void marksEntriesThatCameFromAnEquivalentClassDefinition() throws Exception {
        List<Map<String, String>> list = ancestors();
        assertTrue(list.stream().anyMatch(e -> e.get("ancestorIri").endsWith("#Great") && "true".equals(e.get("fromDefinition"))), list.toString());
        assertTrue(list.stream().filter(e -> e.get("ancestorIri").endsWith("#Grand")).noneMatch(e -> e.containsKey("fromDefinition")), list.toString());
    }
}
