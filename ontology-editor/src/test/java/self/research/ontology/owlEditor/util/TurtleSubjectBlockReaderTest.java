package self.research.ontology.owlEditor.util;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurtleSubjectBlockReaderTest {

    private static final SubjectBlocks.Limits LIMITS = new SubjectBlocks.Limits(10, 500, 256 * 1024);

    private static final String DOC = String.join("\n",
            "@prefix ex: <http://ex.org/> .",
            "@prefix owl: <http://www.w3.org/2002/07/owl#> .",
            "",
            "ex:Pizza a owl:Class ;",
            "    ex:note \"not the end . # still a string\" ;",
            "    ex:link <http://ex.org/a.b#c> ;",
            "    ex:size 1.5 ;",
            "    ex:long \"\"\"line one .",
            "line two # with hash",
            "\"\"\" .",
            "# ex:Pizza a ex:Commented .",
            "ex:PizzaBase a owl:Class .",
            "ex:Other ex:uses ex:Pizza .",
            "<http://ex.org/Pizza> ex:again \"x\" .");

    private static SubjectBlocks read(String doc, String identifier, SubjectBlocks.Limits limits) throws IOException {
        return TurtleSubjectBlockReader.read(new BufferedReader(new StringReader(doc)), identifier, limits);
    }

    @Test
    void returnsTheWholeMultiLineBlockWithExactRangeAndVerbatimText() throws IOException {
        SubjectBlocks result = read(DOC, "ex:Pizza", LIMITS);

        assertEquals(2, result.blocks().size());
        SubjectBlocks.Block first = result.blocks().get(0);
        assertEquals(3, first.startLine());
        assertEquals(7, first.lineCount());
        assertEquals(String.join("\n", DOC.split("\n", -1)).split("\n")[3], first.text().split("\n")[0]);
        assertTrue(first.text().endsWith("\"\"\" ."));
        assertFalse(first.truncated());
        SubjectBlocks.Block second = result.blocks().get(1);
        assertEquals(13, second.startLine());
        assertEquals(1, second.lineCount());
        assertEquals("<http://ex.org/Pizza> ex:again \"x\" .", second.text());
        assertFalse(result.moreBlocks());
    }

    @Test
    void fullIriTargetMatchesPrefixedSubjectsAndIgnoresObjectsCommentsAndLongerNames() throws IOException {
        SubjectBlocks result = read(DOC, "http://ex.org/Pizza", LIMITS);

        assertEquals(List.of(3L, 13L), result.blocks().stream().map(SubjectBlocks.Block::startLine).toList());
    }

    @Test
    void angleBracketTargetIsAccepted() throws IOException {
        SubjectBlocks result = read(DOC, "<http://ex.org/PizzaBase>", LIMITS);

        assertEquals(1, result.blocks().size());
        assertEquals(11, result.blocks().get(0).startLine());
        assertEquals("ex:PizzaBase a owl:Class .", result.blocks().get(0).text());
    }

    @Test
    void sparqlStylePrefixesAreUnderstood() throws IOException {
        String doc = String.join("\n",
                "PREFIX ex: <http://ex.org/>",
                "ex:A a ex:B .",
                "ex:C a ex:B .");

        SubjectBlocks result = read(doc, "http://ex.org/A", LIMITS);

        assertEquals(1, result.blocks().size());
        assertEquals(1, result.blocks().get(0).startLine());
        assertEquals("ex:A a ex:B .", result.blocks().get(0).text());
    }

    @Test
    void prefixedNamesWithDotsAndBlankNodeListsDoNotEndTheStatementEarly() throws IOException {
        String doc = String.join("\n",
                "@prefix ex: <http://ex.org/> .",
                "ex:a.b ex:p [ ex:q 2 ] ;",
                "    ex:r ( 1 2.0 ) .",
                "ex:next ex:p 1 .");

        SubjectBlocks result = read(doc, "ex:a.b", LIMITS);

        assertEquals(1, result.blocks().size());
        assertEquals(1, result.blocks().get(0).startLine());
        assertEquals(2, result.blocks().get(0).lineCount());
    }

    @Test
    void statementsOnTheSameLineAreMergedIntoOneBlock() throws IOException {
        String doc = String.join("\n",
                "@prefix ex: <http://ex.org/> .",
                "ex:A ex:p 1 . ex:A ex:q 2 .",
                "ex:A ex:r 3 .");

        SubjectBlocks result = read(doc, "ex:A", LIMITS);

        assertEquals(2, result.blocks().size());
        assertEquals("1-1", range(result.blocks().get(0)));
        assertEquals("2-1", range(result.blocks().get(1)));
    }

    @Test
    void aStatementStartingMidLineAfterAnotherSubjectIsFound() throws IOException {
        String doc = String.join("\n",
                "@prefix ex: <http://ex.org/> .",
                "ex:B ex:p 1 . ex:A ex:q 2 ;",
                "    ex:r 3 .");

        SubjectBlocks result = read(doc, "ex:A", LIMITS);

        assertEquals(1, result.blocks().size());
        assertEquals("1-2", range(result.blocks().get(0)));
    }

    @Test
    void longBlocksAreTruncatedAtTheLineCapAndSaySo() throws IOException {
        StringBuilder doc = new StringBuilder("@prefix ex: <http://ex.org/> .\nex:A a ex:B ;\n");
        for (int i = 0; i < 20; i++) {
            doc.append("    ex:p").append(i).append(" ").append(i).append(" ;\n");
        }
        doc.append("    ex:last 1 .\nex:A ex:after 2 .\n");

        SubjectBlocks result = read(doc.toString(), "ex:A", new SubjectBlocks.Limits(10, 5, 1024));

        assertEquals(2, result.blocks().size());
        SubjectBlocks.Block first = result.blocks().get(0);
        assertTrue(first.truncated());
        assertEquals(5, first.lineCount());
        assertEquals(5, first.text().split("\n").length);
        assertEquals(23, result.blocks().get(1).startLine());
        assertFalse(result.blocks().get(1).truncated());
    }

    @Test
    void stopsAfterTheBlockCapAndReportsThatMoreExist() throws IOException {
        StringBuilder doc = new StringBuilder("@prefix ex: <http://ex.org/> .\n");
        for (int i = 0; i < 5; i++) {
            doc.append("ex:A ex:p ").append(i).append(" .\n");
        }

        SubjectBlocks capped = read(doc.toString(), "ex:A", new SubjectBlocks.Limits(3, 500, 1024));
        SubjectBlocks exact = read(doc.toString(), "ex:A", new SubjectBlocks.Limits(5, 500, 1024));

        assertEquals(3, capped.blocks().size());
        assertTrue(capped.moreBlocks());
        assertEquals(5, exact.blocks().size());
        assertFalse(exact.moreBlocks());
    }

    @Test
    void unknownSubjectReturnsNoBlocks() throws IOException {
        SubjectBlocks result = read(DOC, "ex:Missing", LIMITS);

        assertTrue(result.blocks().isEmpty());
        assertFalse(result.moreBlocks());
    }

    private static String range(SubjectBlocks.Block block) {
        return block.startLine() + "-" + block.lineCount();
    }
}
