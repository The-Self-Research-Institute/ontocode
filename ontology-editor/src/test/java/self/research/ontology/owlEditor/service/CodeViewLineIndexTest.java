package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeViewLineIndexTest {

    private static Path write(String content) throws IOException {
        Path file = Files.createTempFile("line-index-", ".ttl");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static CodeViewLineIndex index(Path file) throws IOException {
        return CodeViewLineIndex.build(file, Files.getLastModifiedTime(file).toMillis(), Files.size(file));
    }

    private static List<String> naiveLines(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static void assertPagesMatchNaiveReading(Path file, long[] starts, int[] counts) throws IOException {
        CodeViewLineIndex index = index(file);
        List<String> lines = naiveLines(file);
        assertTrue(index.usable());
        assertEquals(lines.size(), index.totalLines());
        for (long start : starts) {
            for (int count : counts) {
                int from = (int) Math.min(start, lines.size());
                int to = (int) Math.min(start + count, lines.size());
                String expected = String.join("\n", lines.subList(from, to));
                StorageManager.CodeViewPage page = index.readPage(file, start, count);
                assertEquals(expected, page.content(), "start=" + start + " count=" + count);
                assertEquals(to - from, page.lineCount());
                assertEquals(lines.size(), page.totalLines());
            }
        }
    }

    private static String numberedLines(int count, String terminator, boolean trailing) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append("ex:s").append(i).append(" rdfs:label \"é ").append(i).append(" 犬\"@ja .");
            if (i < count - 1 || trailing) {
                sb.append(terminator);
            }
        }
        return sb.toString();
    }

    @Test
    void pagesAcrossCheckpointsMatchSequentialReadingWithLf() throws IOException {
        Path file = write(numberedLines(5_000, "\n", true));
        assertPagesMatchNaiveReading(file, new long[]{0, 1, 1023, 1024, 1025, 2047, 4096, 4999, 5000, 9000},
                new int[]{1, 2, 50, 2000});
    }

    @Test
    void crlfFilesAreIndexedLikeReadLine() throws IOException {
        Path file = write(numberedLines(3_000, "\r\n", true));
        assertPagesMatchNaiveReading(file, new long[]{0, 1023, 1024, 2999}, new int[]{1, 3, 1500});
    }

    @Test
    void missingTrailingNewlineStillCountsTheLastLine() throws IOException {
        Path file = write(numberedLines(1_025, "\n", false));
        assertPagesMatchNaiveReading(file, new long[]{0, 1023, 1024}, new int[]{1, 5});
    }

    @Test
    void emptyFileHasNoLines() throws IOException {
        Path file = write("");
        CodeViewLineIndex index = index(file);
        assertEquals(0, index.totalLines());
        assertEquals("", index.readPage(file, 0, 10).content());
    }

    @Test
    void loneCarriageReturnsMakeTheIndexUnusableSoCallersFallBack() throws IOException {
        assertFalse(index(write("a\rb\nc")).usable());
        assertFalse(index(write("a\nb\r")).usable());
    }

    @Test
    void indexKnowsWhenTheFileChanged() throws IOException {
        Path file = write("a\nb\n");
        CodeViewLineIndex index = index(file);
        assertTrue(index.describes(Files.getLastModifiedTime(file).toMillis(), Files.size(file)));
        assertFalse(index.describes(Files.getLastModifiedTime(file).toMillis(), Files.size(file) + 1));
    }
}
