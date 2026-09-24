package self.research.ontology.owlEditor.util;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdfSourceDiagnosticsTest {

    @TempDir
    Path dir;

    private Path write(String content) throws IOException {
        Path file = dir.resolve("content.ttl");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static RdfSourceDiagnostics.Result collect(Path file, RDFFormat format) throws IOException {
        return RdfSourceDiagnostics.collect(file, format, line -> true, 50, Duration.ofSeconds(20));
    }

    @Test
    void validTurtleHasNoIssues() throws IOException {
        Path file = write("@prefix ex: <http://ex.org/> .\nex:A a ex:B .\n");

        RdfSourceDiagnostics.Result result = collect(file, RDFFormat.TURTLE);

        assertTrue(result.issues().isEmpty());
        assertFalse(result.stoppedEarly());
        assertFalse(result.capped());
    }

    @Test
    void syntaxErrorIsReportedOnceAsFatalWithItsLineNumber() throws IOException {
        Path file = write("@prefix ex: <http://ex.org/> .\nex:A a ex:B .\nex:C a ex:D ;\n    ex:p .\nex:E a ex:F .\n");

        RdfSourceDiagnostics.Result result = collect(file, RDFFormat.TURTLE);

        assertEquals(1, result.issues().size());
        RdfSourceDiagnostics.Issue issue = result.issues().get(0);
        assertEquals(RdfSourceDiagnostics.Level.FATAL, issue.level());
        assertEquals(4, issue.line());
        assertTrue(result.stoppedEarly());
        assertEquals(4, result.fatalLine());
        assertFalse(issue.message().isBlank());
        assertFalse(issue.message().contains("[line 4"));
    }

    @Test
    void nonFatalErrorsAreCollectedAndParsingContinuesPastThem() throws IOException {
        Path file = write(String.join("\n",
                "@prefix ex: <http://ex.org/> .",
                "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .",
                "ex:A ex:n \"abc\"^^xsd:integer .",
                "ex:B ex:n \"1\"^^xsd:integer .",
                "ex:C ex:n \"x\"^^xsd:boolean .",
                "ex:D ex:n 1 .",
                ""));

        RdfSourceDiagnostics.Result result = collect(file, RDFFormat.TURTLE);

        assertEquals(2, result.issues().size(), result.issues().toString());
        assertEquals(3, result.issues().get(0).line());
        assertEquals(5, result.issues().get(1).line());
        assertEquals(RdfSourceDiagnostics.Level.ERROR, result.issues().get(0).level());
        assertFalse(result.stoppedEarly());
    }

    @Test
    void lineFilterKeepsOnlyIssuesOnAcceptedLines() throws IOException {
        Path file = write(String.join("\n",
                "@prefix ex: <http://ex.org/> .",
                "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .",
                "ex:A ex:n \"abc\"^^xsd:integer .",
                "ex:C ex:n \"x\"^^xsd:boolean .",
                ""));

        RdfSourceDiagnostics.Result result = RdfSourceDiagnostics.collect(
                file, RDFFormat.TURTLE, line -> line == 4, 50, Duration.ofSeconds(20));

        assertEquals(1, result.issues().size());
        assertEquals(4, result.issues().get(0).line());
    }

    @Test
    void capsTheNumberOfIssuesAndSaysSo() throws IOException {
        StringBuilder doc = new StringBuilder("@prefix ex: <http://ex.org/> .\n"
                + "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n");
        for (int i = 0; i < 10; i++) {
            doc.append("ex:A").append(i).append(" ex:n \"bad\"^^xsd:integer .\n");
        }
        Path file = write(doc.toString());

        RdfSourceDiagnostics.Result result = RdfSourceDiagnostics.collect(
                file, RDFFormat.TURTLE, line -> true, 3, Duration.ofSeconds(20));

        assertEquals(3, result.issues().size());
        assertTrue(result.capped());
    }

    @Test
    void anExpiredDeadlineStopsAndIsReported() throws IOException {
        Path file = write("@prefix ex: <http://ex.org/> .\n"
                + "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n"
                + "ex:A ex:n \"bad\"^^xsd:integer .\nex:B ex:n \"bad\"^^xsd:integer .\n");

        RdfSourceDiagnostics.Result result = RdfSourceDiagnostics.collect(
                file, RDFFormat.TURTLE, line -> true, 50, Duration.ZERO);

        assertTrue(result.timedOut());
        assertEquals(1, result.issues().size());
    }

    @Test
    void malformedRdfXmlIsReportedWithALine() throws IOException {
        Path file = dir.resolve("content.owl");
        Files.writeString(file, String.join("\n",
                "<?xml version=\"1.0\"?>",
                "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">",
                "  <rdf:Description rdf:about=\"http://ex.org/A\">",
                "  </rdf:Descriptio>",
                "</rdf:RDF>"), StandardCharsets.UTF_8);

        RdfSourceDiagnostics.Result result = collect(file, RDFFormat.RDFXML);

        assertFalse(result.issues().isEmpty());
        RdfSourceDiagnostics.Issue last = result.issues().get(result.issues().size() - 1);
        assertEquals(RdfSourceDiagnostics.Level.FATAL, last.level());
        assertEquals(4, last.line());
    }

    @Test
    void externalEntitiesAreNotResolved() throws IOException {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "TOPSECRET", StandardCharsets.UTF_8);
        Path file = dir.resolve("xxe.owl");
        Files.writeString(file, String.join("\n",
                "<?xml version=\"1.0\"?>",
                "<!DOCTYPE rdf:RDF [ <!ENTITY x SYSTEM \"" + secret.toUri() + "\"> ]>",
                "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"",
                "   xmlns:ex=\"http://ex.org/\">",
                "  <rdf:Description rdf:about=\"http://ex.org/A\"><ex:p>&x;</ex:p></rdf:Description>",
                "</rdf:RDF>"), StandardCharsets.UTF_8);

        RdfSourceDiagnostics.Result result = collect(file, RDFFormat.RDFXML);

        assertTrue(result.issues().stream().noneMatch(issue -> issue.message().contains("TOPSECRET")));
    }

    @Test
    void mentionLinesFindPrefixedAndFullFormsButNotLongerNames() throws IOException {
        String doc = String.join("\n",
                "@prefix ex: <http://ex.org/> .",
                "ex:A a ex:B .",
                "ex:AB a ex:B .",
                "<http://ex.org/A> ex:p 1 .",
                "ex:C ex:p ex:A.",
                "ex:D ex:p \"ex:A is in a string\" .");

        IdentifierMentionLines.Result result = IdentifierMentionLines.scan(
                new BufferedReader(new StringReader(doc)), "http://ex.org/A", false, 100);

        assertEquals(java.util.Set.of(2L, 4L, 5L, 6L), result.lines());
        assertFalse(result.capped());
    }

    @Test
    void mentionLinesUnderstandXmlEntitiesAndCap() throws IOException {
        String doc = String.join("\n",
                "<!DOCTYPE rdf:RDF [ <!ENTITY ex \"http://ex.org/\" > ]>",
                "<rdf:RDF xmlns:ex=\"http://ex.org/\">",
                "<owl:Class rdf:about=\"&ex;A\"/>",
                "<owl:Class rdf:about=\"http://ex.org/A\"/>",
                "<ex:A/>",
                "<owl:Class rdf:about=\"http://ex.org/A#x\"/>");

        IdentifierMentionLines.Result all = IdentifierMentionLines.scan(
                new BufferedReader(new StringReader(doc)), "http://ex.org/A", true, 100);
        IdentifierMentionLines.Result capped = IdentifierMentionLines.scan(
                new BufferedReader(new StringReader(doc)), "http://ex.org/A", true, 2);

        assertEquals(java.util.Set.of(3L, 4L, 5L), all.lines());
        assertTrue(capped.capped());
        assertEquals(2, capped.lines().size());
    }
}
