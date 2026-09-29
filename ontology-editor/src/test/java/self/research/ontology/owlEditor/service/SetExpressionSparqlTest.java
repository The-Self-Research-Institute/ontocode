package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SetExpressionSparqlTest {

    private static final String LIST = "  _:list0 rdf:first <http://ex/A> ;\n"
            + "             rdf:rest _:list1 .\n"
            + "  _:list1 rdf:first <http://ex/B> ;\n"
            + "             rdf:rest rdf:nil .\n";

    @Test
    void disjointUnionTrimsMembersInPlace() {
        String[] members = {" http://ex/A ", "http://ex/B"};
        String sparql = SetExpressionSparql.disjointUnion("http://ex/C", members);
        assertEquals("INSERT DATA {\n  <http://ex/C> owl:disjointUnionOf _:list0 .\n" + LIST + "}\n", sparql);
        assertArrayEquals(new String[]{"http://ex/A", "http://ex/B"}, members);
    }

    @Test
    void intersectionUnionAndOneOfUseAnonymousNode() {
        String[] members = {"http://ex/A", " http://ex/B"};
        assertEquals("INSERT DATA {\n  <http://ex/C> rdfs:subClassOf _:intersection .\n"
                + "  _:intersection owl:intersectionOf _:list0 .\n" + LIST + "}\n",
                SetExpressionSparql.intersection("http://ex/C", members, "SubClassOf", "rdfs:subClassOf"));
        assertEquals("INSERT DATA {\n  <http://ex/C> owl:equivalentClass _:union .\n"
                + "  _:union owl:unionOf _:list0 .\n" + LIST + "}\n",
                SetExpressionSparql.union("http://ex/C", members, "EquivalentTo", "owl:equivalentClass"));
        assertEquals("INSERT DATA {\n  <http://ex/C> owl:equivalentClass _:oneOf .\n"
                + "  _:oneOf owl:oneOf _:list0 .\n" + LIST + "}\n",
                SetExpressionSparql.oneOf("http://ex/C", members, null, "owl:equivalentClass"));
    }
}
