package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionSyntaxCheckTest {

    private static final List<String> TURTLE = List.of(
            "# leading comment",
            "@prefix ex: <http://example.org/> .",
            "@prefix owl: <http://www.w3.org/2002/07/owl#> .",
            "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .",
            "",
            "ex:A a owl:Class ;",
            "    rdfs:label \"A\" .",
            "",
            "ex:B a owl:Class ;",
            "    rdfs:subClassOf [ a owl:Restriction ;",
            "        owl:onProperty ex:p ;",
            "        owl:someValuesFrom ex:C ] .",
            "",
            "ex:C a owl:Class .");

    private static final List<String> RDF_XML = List.of(
            "<?xml version=\"1.0\"?>",
            "<!DOCTYPE rdf:RDF [",
            "    <!ENTITY owl \"http://www.w3.org/2002/07/owl#\" >",
            "]>",
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"",
            "    xmlns:owl=\"http://www.w3.org/2002/07/owl#\" xml:base=\"http://example.org/\">",
            "    <owl:Class rdf:about=\"#A\"/>",
            "    <owl:Class rdf:about=\"#B\">",
            "        <rdf:type rdf:resource=\"&owl;Thing\"/>",
            "    </owl:Class>",
            "    <owl:Class rdf:about=\"#C\"/>",
            "</rdf:RDF>");

    private static final List<String> NTRIPLES = List.of(
            "<http://example.org/A> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/2002/07/owl#Class> .",
            "<http://example.org/B> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/2002/07/owl#Class> .");

    private static LineRangeSpliceWriter.SpliceEdit edit(long start, int count, String text) {
        return new LineRangeSpliceWriter.SpliceEdit(start, count, text);
    }

    private static Path write(List<String> lines, String ext) throws Exception {
        Path file = Files.createTempFile("region-", "." + ext);
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    private static boolean fullParse(Path source, String ext, RDFFormat format,
                                     List<LineRangeSpliceWriter.SpliceEdit> edits) throws Exception {
        Path spliced = new LineRangeSpliceWriter().splice(source, ext, edits);
        try (InputStream in = Files.newInputStream(spliced)) {
            var parser = Rio.createParser(format);
            parser.setRDFHandler(new AbstractRDFHandler() {});
            parser.parse(in, "");
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            Files.deleteIfExists(spliced);
        }
    }

    private static boolean check(Path file, String format, RDFFormat rdf, String ext,
                                 List<LineRangeSpliceWriter.SpliceEdit> edits) throws Exception {
        boolean local = RegionSyntaxCheck.parses(file, format, edits);
        if (local) {
            assertTrue(fullParse(file, ext, rdf, edits), "region passed but the whole file does not parse");
        }
        return local;
    }

    @Test
    void validTurtleEditsParseLocally() throws Exception {
        Path file = write(TURTLE, "ttl");
        assertTrue(check(file, "turtle", RDFFormat.TURTLE, "ttl", List.of(edit(6, 1, "    rdfs:label \"A2\" ."))));
        assertTrue(check(file, "turtle", RDFFormat.TURTLE, "ttl", List.of(edit(10, 1, "        owl:onProperty ex:q ;"))));
        assertTrue(check(file, "turtle", RDFFormat.TURTLE, "ttl", List.of(
                edit(6, 1, "    rdfs:label \"A2\" ."), edit(13, 1, "ex:C a owl:Class ; rdfs:label \"C\" ."))));
        assertTrue(check(file, "turtle", RDFFormat.TURTLE, "ttl", List.of(edit(14, 0, "ex:D a owl:Class ."))));
        assertTrue(check(file, "turtle", RDFFormat.TURTLE, "ttl", List.of(edit(7, 0, "ex:N a owl:Class ."))));
    }

    @Test
    void brokenTurtleDefersToTheFullParse() throws Exception {
        Path file = write(TURTLE, "ttl");
        assertFalse(check(file, "turtle", RDFFormat.TURTLE, "ttl", List.of(edit(6, 1, "    rdfs:label \"A2\""))));
        assertFalse(check(file, "turtle", RDFFormat.TURTLE, "ttl", List.of(edit(6, 1, "    rdfs:label \"\"\"open ."))));
        assertFalse(check(file, "turtle", RDFFormat.TURTLE, "ttl", List.of(edit(11, 1, "        owl:someValuesFrom ex:C ."))));
        assertFalse(check(file, "turtle", RDFFormat.TURTLE, "ttl", List.of(edit(6, 1, "    rdfs:label zz:A ."))));
    }

    @Test
    void headerAndOutOfOrderEditsSkipTheLocalCheck() throws Exception {
        Path file = write(TURTLE, "ttl");
        assertFalse(RegionSyntaxCheck.parses(file, "turtle", List.of(edit(0, 0, "ex:Z a owl:Class ."))));
        assertFalse(RegionSyntaxCheck.parses(file, "turtle", List.of(edit(2, 1, "@prefix owl: <http://other/> ."))));
        assertFalse(RegionSyntaxCheck.parses(file, "turtle", List.of(
                edit(13, 1, "ex:C a owl:Class ."), edit(6, 1, "    rdfs:label \"A2\" ."))));
        assertFalse(RegionSyntaxCheck.parses(file, "turtle", List.of(
                edit(6, 0, "ex:X a owl:Class ."), edit(6, 1, "    rdfs:label \"A2\" ."))));
        assertFalse(RegionSyntaxCheck.parses(file, "owlxml", List.of(edit(6, 1, "x"))));
    }

    @Test
    void rdfXmlEditsKeepEntitiesAndStayInsideTheRoot() throws Exception {
        Path file = write(RDF_XML, "owl");
        assertTrue(check(file, "rdfxml", RDFFormat.RDFXML, "owl",
                List.of(edit(8, 1, "        <rdf:type rdf:resource=\"&owl;NamedIndividual\"/>"))));
        assertTrue(check(file, "rdfxml", RDFFormat.RDFXML, "owl",
                List.of(edit(11, 0, "    <owl:Class rdf:about=\"#D\"/>"))));
        assertFalse(check(file, "rdfxml", RDFFormat.RDFXML, "owl",
                List.of(edit(7, 1, "    <owl:Class rdf:about=\"#B\">\n    <owl:Class>"))));
        assertFalse(RegionSyntaxCheck.parses(file, "rdfxml", List.of(edit(12, 0, "<owl:Class rdf:about=\"#E\"/>"))));
        assertFalse(RegionSyntaxCheck.parses(file, "rdfxml", List.of(edit(11, 1, ""))));
    }

    @Test
    void nTriplesUsesTheStricterParser() throws Exception {
        Path file = write(NTRIPLES, "nt");
        assertTrue(check(file, "ntriples", RDFFormat.NTRIPLES, "nt", List.of(edit(1, 1,
                "<http://example.org/B> <http://www.w3.org/2000/01/rdf-schema#label> \"B\" ."))));
        assertFalse(check(file, "ntriples", RDFFormat.NTRIPLES, "nt", List.of(edit(1, 1, "ex:B a ex:C ."))));
    }
}
