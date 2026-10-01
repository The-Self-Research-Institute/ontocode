package self.research.ontology.owlEditor.util;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubjectRangeIndexTest {

    private static SubjectRangeIndex turtle(String text) throws IOException {
        return TurtleSubjectBlockReader.index(new BufferedReader(new StringReader(text)));
    }

    private static SubjectRangeIndex rdfXml(String text) throws IOException {
        return RdfXmlSubjectBlockReader.index(new BufferedReader(new StringReader(text)));
    }

    @Test
    void turtleIndexRecordsEveryStatementWithItsResolvedSubjectAndLines() throws IOException {
        SubjectRangeIndex index = turtle(String.join("\n",
                "@prefix ex: <http://example.org/> .",
                "ex:Dog a ex:Class ;",
                "    ex:label \"a . b\" .",
                "<http://example.org/Cat> a ex:Class .",
                "[] ex:p ex:q .",
                "ex:Dog ex:more \"x\" ."));

        assertTrue(index.complete());
        assertEquals(4, index.blocks().size());
        assertEquals(new SubjectRangeIndex.Block("http://example.org/Dog", 1, 2), index.blocks().get(0));
        assertEquals("http://example.org/Cat", index.blocks().get(1).subject());
        assertTrue(index.blocks().get(2).isBlankSubject());
        assertEquals(2, index.blocksFor("http://example.org/Dog").size());
        assertTrue(index.headerLines().contains(0L));
        assertTrue(index.header().contains("@prefix ex:"));
    }

    @Test
    void overlappingFindsEveryBlockTouchingALineRange() throws IOException {
        SubjectRangeIndex index = turtle(String.join("\n",
                "@prefix ex: <http://example.org/> .", "ex:A ex:p 1 .", "ex:B ex:p 2 ;", "   ex:q 3 .", "ex:C ex:p 4 ."));

        List<SubjectRangeIndex.Block> hits = index.overlapping(3, 4);

        assertEquals(List.of("http://example.org/B", "http://example.org/C"), hits.stream().map(SubjectRangeIndex.Block::subject).toList());
    }

    @Test
    void unterminatedTurtleIsReportedIncomplete() throws IOException {
        assertFalse(turtle("@prefix ex: <http://example.org/> .\nex:A ex:p ex:q").complete());
    }

    @Test
    void rdfXmlIndexUsesTopLevelChildrenAndKeepsTheRootAsHeader() throws IOException {
        SubjectRangeIndex index = rdfXml(String.join("\n",
                "<?xml version=\"1.0\"?>",
                "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xml:base=\"http://example.org/base\"",
                "    xmlns:owl=\"http://www.w3.org/2002/07/owl#\">",
                "    <owl:Class rdf:about=\"#Dog\">",
                "        <owl:subClassOf><owl:Class rdf:about=\"#Nested\"/></owl:subClassOf>",
                "    </owl:Class>",
                "    <owl:Class rdf:ID=\"Cat\"/>",
                "    <rdf:Description rdf:nodeID=\"n1\"/>",
                "</rdf:RDF>"));

        assertTrue(index.complete());
        assertEquals(3, index.blocks().size());
        assertEquals(new SubjectRangeIndex.Block("http://example.org/base#Dog", 3, 5), index.blocks().get(0));
        assertEquals("http://example.org/base#Cat", index.blocks().get(1).subject());
        assertEquals("_:n1", index.blocks().get(2).subject());
        assertTrue(index.header().startsWith("<rdf:RDF"));
        assertEquals("</rdf:RDF>", index.footer());
        assertTrue(index.touchesHeader(1, 2));
        assertTrue(index.touchesHeader(8, 8));
        assertFalse(index.touchesHeader(3, 7));
    }

    @Test
    void rdfXmlWithoutAClosingRootIsIncomplete() throws IOException {
        assertFalse(rdfXml("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n<rdf:Description/>").complete());
    }
}
