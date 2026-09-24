package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantEditSyntaxValidatorTest {

    @Mock
    private StorageManager storageManager;

    @Mock
    private LineRangeSpliceWriter spliceWriter;

    private AssistantEditSyntaxValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        validator = new AssistantEditSyntaxValidator(storageManager, spliceWriter);
        when(storageManager.ensureCodeViewFile(anyString(), anyString())).thenReturn(Path.of("dummy.ttl"));
        when(storageManager.extensionFor(anyString())).thenReturn("ttl");
    }

    @Test
    void validTurtleAfterSplicePasses() throws Exception {
        Path spliced = validTurtleFile();
        when(spliceWriter.splice(any(), anyString(), any())).thenReturn(spliced);

        assertTrue(validator.isValid("proj-1", "turtle", List.of(new LineRangeSpliceWriter.SpliceEdit(1, 1, ":NewClass a owl:Class ."))));
    }

    @Test
    void invalidTurtleAfterSpliceFails() throws Exception {
        Path spliced = Files.createTempFile("syntax-invalid-", ".ttl");
        Files.writeString(spliced, "this is not valid turtle @@@ <<<", StandardCharsets.UTF_8);
        when(spliceWriter.splice(any(), anyString(), any())).thenReturn(spliced);

        assertFalse(validator.isValid("proj-1", "turtle", List.of(new LineRangeSpliceWriter.SpliceEdit(1, 1, "junk"))));
    }

    @Test
    void owlApiFormatsSkipParsingEntirely() throws Exception {
        assertTrue(validator.isValid("proj-1", "functional", List.of(new LineRangeSpliceWriter.SpliceEdit(1, 1, "anything"))));
        assertTrue(validator.isValid("proj-1", "manchester", List.of(new LineRangeSpliceWriter.SpliceEdit(1, 1, "anything"))));
        assertTrue(validator.isValid("proj-1", "owlxml", List.of(new LineRangeSpliceWriter.SpliceEdit(1, 1, "anything"))));
        verify(spliceWriter, never()).splice(any(), anyString(), any());
    }

    @Test
    void spliceFailureIsTreatedAsInvalidNotAnException() throws Exception {
        when(spliceWriter.splice(any(), anyString(), any())).thenThrow(new java.io.IOException("disk full"));

        assertFalse(validator.isValid("proj-1", "turtle", List.of(new LineRangeSpliceWriter.SpliceEdit(1, 1, "text"))));
    }

    private Path validTurtleFile() throws Exception {
        Path tempFile = Files.createTempFile("syntax-valid-", ".ttl");
        Files.writeString(tempFile,
                "@prefix : <http://example.org/> .\n"
                        + "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + ":NewClass a owl:Class .",
                StandardCharsets.UTF_8);
        return tempFile;
    }
}
