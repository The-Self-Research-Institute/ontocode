package self.research.ontology.owlEditor.util;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdfXmlSubjectBlockReaderTest {

    private static final SubjectBlocks.Limits LIMITS = new SubjectBlocks.Limits(10, 500, 256 * 1024);

    private static final String DOC = String.join("\n",
            "<?xml version=\"1.0\"?>",
            "<!DOCTYPE rdf:RDF [",
            "    <!ENTITY ex \"http://ex.org/\" >",
            "]>",
            "<rdf:RDF xmlns=\"http://ex.org/\"",
            "     xml:base=\"http://ex.org/\"",
            "     xmlns:ex=\"http://ex.org/\"",
            "     xmlns:owl=\"http://www.w3.org/2002/07/owl#\"",
            "     xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"",
            "     xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\">",
            "    <!-- <owl:Class rdf:about=\"http://ex.org/Pizza\"/> -->",
            "    <owl:Class",
            "        rdf:about=\"&ex;Pizza\">",
            "        <rdfs:label>Pizza &gt; pie</rdfs:label>",
            "        <rdfs:subClassOf>",
            "            <owl:Class rdf:about=\"http://ex.org/Food\"/>",
            "        </rdfs:subClassOf>",
            "    </owl:Class>",
            "    <owl:Class rdf:about=\"http://ex.org/PizzaBase\"/>",
            "    <owl:NamedIndividual rdf:about=\"http://ex.org/Pizza\"><rdfs:comment>x</rdfs:comment></owl:NamedIndividual>",
            "</rdf:RDF>");

    private static SubjectBlocks read(String doc, String identifier, SubjectBlocks.Limits limits) throws IOException {
        return RdfXmlSubjectBlockReader.read(new BufferedReader(new StringReader(doc)), identifier, limits);
    }

    @Test
    void returnsTheElementFromItsMultiLineStartTagThroughItsCloseTag() throws IOException {
        SubjectBlocks result = read(DOC, "http://ex.org/Pizza", LIMITS);

        assertEquals(2, result.blocks().size());
        SubjectBlocks.Block first = result.blocks().get(0);
        assertEquals(11, first.startLine());
        assertEquals(7, first.lineCount());
        assertTrue(first.text().startsWith("    <owl:Class\n        rdf:about=\"&ex;Pizza\">"));
        assertTrue(first.text().endsWith("    </owl:Class>"));
        SubjectBlocks.Block second = result.blocks().get(1);
        assertEquals(19, second.startLine());
        assertEquals(1, second.lineCount());
        assertTrue(second.text().contains("owl:NamedIndividual"));
    }

    @Test
    void prefixedTargetsResolveThroughXmlnsDeclarations() throws IOException {
        SubjectBlocks result = read(DOC, "ex:PizzaBase", LIMITS);

        assertEquals(1, result.blocks().size());
        assertEquals(18, result.blocks().get(0).startLine());
        assertEquals("    <owl:Class rdf:about=\"http://ex.org/PizzaBase\"/>", result.blocks().get(0).text());
    }

    @Test
    void nestedSelfClosingElementsAreFoundOnTheirOwnLine() throws IOException {
        SubjectBlocks result = read(DOC, "http://ex.org/Food", LIMITS);

        assertEquals(1, result.blocks().size());
        assertEquals("15-1", result.blocks().get(0).startLine() + "-" + result.blocks().get(0).lineCount());
    }

    @Test
    void relativeAboutValuesResolveAgainstXmlBase() throws IOException {
        String doc = String.join("\n",
                "<rdf:RDF xml:base=\"http://ex.org/onto\"",
                "     xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">",
                "  <rdf:Description rdf:about=\"#Thing\">",
                "  </rdf:Description>",
                "</rdf:RDF>");

        SubjectBlocks result = read(doc, "http://ex.org/onto#Thing", LIMITS);

        assertEquals(1, result.blocks().size());
        assertEquals(2, result.blocks().get(0).startLine());
        assertEquals(2, result.blocks().get(0).lineCount());
    }

    @Test
    void aDifferentRdfPrefixIsHonoured() throws IOException {
        String doc = String.join("\n",
                "<r:RDF xmlns:r=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">",
                "  <r:Description r:about=\"http://ex.org/A\"/>",
                "</r:RDF>");

        SubjectBlocks result = read(doc, "http://ex.org/A", LIMITS);

        assertEquals(1, result.blocks().size());
        assertEquals(1, result.blocks().get(0).startLine());
    }

    @Test
    void capsBlocksAndLines() throws IOException {
        StringBuilder doc = new StringBuilder("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n");
        for (int i = 0; i < 4; i++) {
            doc.append("  <rdf:Description rdf:about=\"http://ex.org/A\">\n");
            for (int j = 0; j < 6; j++) {
                doc.append("    <p>").append(j).append("</p>\n");
            }
            doc.append("  </rdf:Description>\n");
        }
        doc.append("</rdf:RDF>\n");

        SubjectBlocks result = read(doc.toString(), "http://ex.org/A", new SubjectBlocks.Limits(2, 3, 4096));

        assertEquals(2, result.blocks().size());
        assertTrue(result.moreBlocks());
        assertTrue(result.blocks().get(0).truncated());
        assertEquals(3, result.blocks().get(0).lineCount());
        assertEquals(9, result.blocks().get(1).startLine());
    }

    @Test
    void unknownSubjectReturnsNothing() throws IOException {
        SubjectBlocks result = read(DOC, "http://ex.org/Nope", LIMITS);

        assertTrue(result.blocks().isEmpty());
        assertFalse(result.moreBlocks());
    }
}
