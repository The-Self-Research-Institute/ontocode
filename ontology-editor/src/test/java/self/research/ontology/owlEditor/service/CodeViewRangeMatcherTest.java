package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeViewRangeMatcherTest {

    private StorageManager storageManager;
    private CodeViewRangeMatcher matcher;
    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        storageManager = mock(StorageManager.class);
        matcher = new CodeViewRangeMatcher(storageManager);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            lines.add("line " + i);
        }
        file = Files.createTempFile("range-matcher-", ".ttl");
        Files.write(file, lines, StandardCharsets.UTF_8);
        when(storageManager.ensureCodeViewFile("p", "turtle")).thenReturn(file);
    }

    private static CodeViewRangeMatcher.ExpectedRange range(long start, int count, String text) {
        return new CodeViewRangeMatcher.ExpectedRange(start, count, text);
    }

    @Test
    void matchesManyRangesInOneReadRegardlessOfInputOrder() throws IOException {
        List<CodeViewRangeMatcher.ExpectedRange> ranges = new ArrayList<>();
        for (int i = 9_990; i >= 0; i -= 10) {
            ranges.add(range(i, 1, "line " + i));
        }

        assertTrue(matcher.allMatch("p", "turtle", ranges));
        verify(storageManager, times(1)).ensureCodeViewFile("p", "turtle");
        verify(storageManager, never()).readCodeViewPage(anyString(),
                anyString(), anyLong(),
                anyInt());
    }

    @Test
    void multiLineRangeMustMatchExactly() {
        assertTrue(matcher.allMatch("p", "turtle", List.of(range(5, 2, "line 5\nline 6"))));
        assertFalse(matcher.allMatch("p", "turtle", List.of(range(5, 2, "line 5\nline 7"))));
    }

    @Test
    void insertionsAreIgnoredBecauseTheyHaveNothingToCompare() {
        assertTrue(matcher.allMatch("p", "turtle", List.of(range(3, 0, ""), range(4, 1, "line 4"))));
    }

    @Test
    void rangePastTheEndOfTheFileDoesNotMatch() {
        assertFalse(matcher.allMatch("p", "turtle", List.of(range(9_999, 2, "line 9999\nline 10000"))));
    }

    @Test
    void overlappingRangesAreRejected() {
        assertFalse(matcher.allMatch("p", "turtle", List.of(range(1, 3, "line 1\nline 2\nline 3"), range(2, 1, "line 2"))));
    }

    @Test
    void missingSourceFileDoesNotMatch() throws IOException {
        when(storageManager.ensureCodeViewFile("p", "rdfxml")).thenReturn(null);
        assertFalse(matcher.allMatch("p", "rdfxml", List.of(range(0, 1, "line 0"))));
    }

    @Test
    void unreadableSourceFailsClosed() throws IOException {
        when(storageManager.ensureCodeViewFile("p", "ntriples")).thenThrow(new IOException("export failed"));
        assertFalse(matcher.allMatch("p", "ntriples", List.of(range(0, 1, "line 0"))));
    }

    @Test
    void emptyOrInsertOnlyListMatchesWithoutReadingTheFile() throws IOException {
        assertTrue(matcher.allMatch("p", "turtle", List.of()));
        assertTrue(matcher.allMatch("p", "turtle", List.of(range(2, 0, ""))));
        verify(storageManager, never()).ensureCodeViewFile("p", "turtle");
    }

    @Test
    void unicodeContentIsComparedExactly() throws IOException {
        Path unicode = Files.createTempFile("range-matcher-u-", ".ttl");
        Files.writeString(unicode, "ex:a rdfs:label \"Chien\"@fr .\nex:b rdfs:label \"犬\"@ja .\n", StandardCharsets.UTF_8);
        when(storageManager.ensureCodeViewFile("p", "unicode")).thenReturn(unicode);
        assertTrue(matcher.allMatch("p", "unicode", List.of(range(1, 1, "ex:b rdfs:label \"犬\"@ja ."))));
    }
}
