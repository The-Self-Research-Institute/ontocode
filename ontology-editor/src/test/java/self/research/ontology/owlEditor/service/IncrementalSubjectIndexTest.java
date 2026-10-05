package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.util.SubjectRangeIndex;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalSubjectIndexTest {

    private static final List<String> TURTLE = List.of(
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
            "ex:C a owl:Class .",
            "",
            "ex:D a owl:Class ;",
            "    rdfs:comment \"\"\"multi",
            "line\"\"\" .",
            "",
            "ex:E a owl:Class .");

    private static final List<String> RDF_XML = List.of(
            "<?xml version=\"1.0\"?>",
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"",
            "    xmlns:owl=\"http://www.w3.org/2002/07/owl#\" xml:base=\"http://example.org/\">",
            "    <owl:Class rdf:about=\"#A\"/>",
            "    <owl:Class rdf:about=\"#B\">",
            "        <rdfs:label xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\">B</rdfs:label>",
            "    </owl:Class>",
            "    <owl:Class rdf:about=\"#C\"/>",
            "</rdf:RDF>");

    private record Case(long start, int count, List<String> replacement) {
    }

    private static Path write(List<String> lines, String ext) throws Exception {
        Path file = Files.createTempFile("inc-", "." + ext);
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    private static List<String> apply(List<String> lines, List<Case> cases) {
        List<String> out = new ArrayList<>(lines);
        for (int i = cases.size() - 1; i >= 0; i--) {
            Case c = cases.get(i);
            for (int n = 0; n < c.count(); n++) {
                out.remove((int) c.start());
            }
            out.addAll((int) c.start(), c.replacement());
        }
        return out;
    }

    private static Optional<SubjectRangeIndex> derive(List<String> before, String format, String ext, Case... cases)
            throws Exception {
        List<Case> list = Arrays.asList(cases);
        Path oldFile = write(before, ext);
        Path newFile = write(apply(before, list), ext);
        SubjectRangeIndex old = CodeViewSubjectIndex.build(oldFile, format);
        List<TriplePatchPlanner.Edit> edits = list.stream()
                .map(c -> new TriplePatchPlanner.Edit(c.start(), c.count(), c.replacement().size())).toList();
        Optional<SubjectRangeIndex> derived = IncrementalSubjectIndex.derive(old, edits, newFile, format);
        derived.ifPresent(index -> assertSameAsFullBuild(index, newFile, format));
        return derived;
    }

    private static void assertSameAsFullBuild(SubjectRangeIndex derived, Path newFile, String format) {
        try {
            SubjectRangeIndex full = CodeViewSubjectIndex.build(newFile, format);
            assertEquals(full.blocks(), derived.blocks());
            assertEquals(full.headerLines(), derived.headerLines());
            assertEquals(full.complete(), derived.complete());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void literalChangeInsideABlockMatchesAFullRebuild() throws Exception {
        assertTrue(derive(TURTLE, "turtle", "ttl", new Case(5, 1, List.of("    rdfs:label \"A2\" ."))).isPresent());
    }

    @Test
    void addedLinesShiftEveryLaterBlock() throws Exception {
        assertTrue(derive(TURTLE, "turtle", "ttl",
                new Case(5, 1, List.of("    rdfs:label \"A\" ;", "    rdfs:comment \"x\" ;", "    rdfs:seeAlso ex:B ."))).isPresent());
    }

    @Test
    void newAndDeletedBlocksMatchAFullRebuild() throws Exception {
        assertTrue(derive(TURTLE, "turtle", "ttl", new Case(12, 0, List.of("ex:New a owl:Class .", ""))).isPresent());
        assertTrue(derive(TURTLE, "turtle", "ttl", new Case(12, 2, List.of())).isPresent());
    }

    @Test
    void editsInsideRestrictionsAndMultiLineStringsMatchAFullRebuild() throws Exception {
        assertTrue(derive(TURTLE, "turtle", "ttl", new Case(10, 1, List.of("        owl:allValuesFrom ex:E ] ."))).isPresent());
        assertTrue(derive(TURTLE, "turtle", "ttl", new Case(15, 2, List.of("    rdfs:comment \"single\" ."))).isPresent());
    }

    @Test
    void twoEditsFarApartMatchAFullRebuild() throws Exception {
        assertTrue(derive(TURTLE, "turtle", "ttl",
                new Case(5, 1, List.of("    rdfs:label \"A\" ;", "    rdfs:comment \"a\" .")),
                new Case(18, 1, List.of("ex:E a owl:Class ;", "    rdfs:label \"E\" ."))).isPresent());
    }

    @Test
    void anEditThatLeavesAStatementOpenFallsBack() throws Exception {
        assertTrue(derive(TURTLE, "turtle", "ttl", new Case(12, 1, List.of("ex:C a owl:Class ;"))).isEmpty());
        assertTrue(derive(TURTLE, "turtle", "ttl", new Case(15, 2, List.of("    rdfs:comment \"\"\"never closed"))).isEmpty());
    }

    @Test
    void anEditToThePrefixHeaderFallsBack() throws Exception {
        assertTrue(derive(TURTLE, "turtle", "ttl", new Case(0, 1, List.of("@prefix ex: <http://other.org/> ."))).isEmpty());
    }

    @Test
    void anEditThatAddsAPrefixDeclarationFallsBack() throws Exception {
        assertTrue(derive(TURTLE, "turtle", "ttl", new Case(3, 0, List.of("@prefix vet: <http://vet.example/> ."))).isEmpty());
    }

    @Test
    void rdfXmlEditsMatchAFullRebuild() throws Exception {
        assertTrue(derive(RDF_XML, "rdfxml", "rdf", new Case(3, 1, List.of("    <owl:Class rdf:about=\"#A\">",
                "        <owl:equivalentClass rdf:resource=\"#C\"/>", "    </owl:Class>"))).isPresent());
        assertTrue(derive(RDF_XML, "rdfxml", "rdf", new Case(7, 0, List.of("    <owl:Class rdf:about=\"#D\"/>"))).isPresent());
    }

    @Test
    void rdfXmlInsertJustBeforeTheClosingTagMatchesAFullRebuild() throws Exception {
        assertTrue(derive(RDF_XML, "rdfxml", "rdf", new Case(8, 0, List.of("    <owl:Class rdf:about=\"#D\"/>"))).isPresent());
        assertTrue(derive(RDF_XML, "rdfxml", "rdf", new Case(9, 0, List.of("<owl:Class rdf:about=\"#E\"/>"))).isEmpty());
    }

    @Test
    void rdfXmlEditThatLeavesAnElementOpenFallsBack() throws Exception {
        assertTrue(derive(RDF_XML, "rdfxml", "rdf", new Case(3, 1, List.of("    <owl:Class rdf:about=\"#A\">"))).isEmpty());
    }
}
