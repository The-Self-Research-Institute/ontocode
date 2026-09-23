package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.service.LineRangeSpliceWriter.SpliceEdit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LineRangeSpliceWriterTest {

    private final LineRangeSpliceWriter writer = new LineRangeSpliceWriter();

    private Path sourceWith(String... lines) throws Exception {
        Path file = Files.createTempFile("splice-src-", ".ttl");
        Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
        return file;
    }

    private String readAll(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    void singleEditReplacesExactLines() throws Exception {
        Path source = sourceWith("a", "b", "c", "d");
        Path result = writer.splice(source, "ttl", List.of(new SpliceEdit(1, 2, "X")));
        assertEquals("a\nX\nd\n", readAll(result));
    }

    @Test
    void multipleNonOverlappingEditsBothApply() throws Exception {
        Path source = sourceWith("a", "b", "c", "d", "e");
        Path result = writer.splice(source, "ttl",
                List.of(new SpliceEdit(0, 1, "A"), new SpliceEdit(3, 1, "D")));
        assertEquals("A\nb\nc\nD\ne\n", readAll(result));
    }

    @Test
    void adjacentEditsBothApplyWithoutGap() throws Exception {
        Path source = sourceWith("a", "b", "c", "d");
        Path result = writer.splice(source, "ttl",
                List.of(new SpliceEdit(1, 1, "B"), new SpliceEdit(2, 1, "C")));
        assertEquals("a\nB\nC\nd\n", readAll(result));
    }

    @Test
    void pureInsertionKeepsOriginalLineAfterIt() throws Exception {
        Path source = sourceWith("a", "b", "c");
        Path result = writer.splice(source, "ttl", List.of(new SpliceEdit(1, 0, "NEW")));
        assertEquals("a\nNEW\nb\nc\n", readAll(result));
    }

    @Test
    void deletionRemovesLinesWithoutInsertingBlank() throws Exception {
        Path source = sourceWith("a", "b", "c", "d");
        Path result = writer.splice(source, "ttl", List.of(new SpliceEdit(1, 2, "")));
        assertEquals("a\nd\n", readAll(result));
    }

    @Test
    void editAtFileStart() throws Exception {
        Path source = sourceWith("a", "b", "c");
        Path result = writer.splice(source, "ttl", List.of(new SpliceEdit(0, 1, "FIRST")));
        assertEquals("FIRST\nb\nc\n", readAll(result));
    }

    @Test
    void editAtFileEnd() throws Exception {
        Path source = sourceWith("a", "b", "c");
        Path result = writer.splice(source, "ttl", List.of(new SpliceEdit(2, 1, "LAST")));
        assertEquals("a\nb\nLAST\n", readAll(result));
    }

    @Test
    void insertionAtEndOfFileAppends() throws Exception {
        Path source = sourceWith("a", "b");
        Path result = writer.splice(source, "ttl", List.of(new SpliceEdit(2, 0, "c")));
        assertEquals("a\nb\nc\n", readAll(result));
    }

    @Test
    void newTextWithEmbeddedMultipleLinesExpandsCorrectly() throws Exception {
        Path source = sourceWith("a", "b", "c");
        Path result = writer.splice(source, "ttl", List.of(new SpliceEdit(1, 1, "x\ny\nz")));
        assertEquals("a\nx\ny\nz\nc\n", readAll(result));
    }

    @Test
    void newTextWithEmbeddedBlankLinePreserved() throws Exception {
        Path source = sourceWith("a", "b", "c");
        Path result = writer.splice(source, "ttl", List.of(new SpliceEdit(1, 1, "x\n\nz")));
        assertEquals("a\nx\n\nz\nc\n", readAll(result));
    }

    @Test
    void noEditsCopiesFileVerbatim() throws Exception {
        Path source = sourceWith("a", "b", "c");
        Path result = writer.splice(source, "ttl", List.of());
        assertEquals("a\nb\nc\n", readAll(result));
    }
}
