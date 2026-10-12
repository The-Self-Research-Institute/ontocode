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

    @Test
    void jsonLdIsParsedAsJsonLdNotAsRdfXml() throws Exception {
        Path spliced = Files.createTempFile("syntax-jsonld-", ".jsonld");
        Files.writeString(spliced, "[{\"@id\":\"http://example.org/A\",\"@type\":[\"http://www.w3.org/2002/07/owl#Class\"]}]",
                StandardCharsets.UTF_8);
        when(spliceWriter.splice(any(), anyString(), any())).thenReturn(spliced);

        AssistantEditSyntaxValidator.SyntaxResult result =
                validator.check("proj-1", "jsonld", List.of(new LineRangeSpliceWriter.SpliceEdit(0, 1, "x")));

        assertTrue(result.valid(), result.detail());
    }

    @Test
    void failureDetailCarriesTheParserMessage() throws Exception {
        Path spliced = Files.createTempFile("syntax-invalid-", ".ttl");
        Files.writeString(spliced, "@prefix : <http://example.org/> .\n:A :b :c @@@ .", StandardCharsets.UTF_8);
        when(spliceWriter.splice(any(), anyString(), any())).thenReturn(spliced);

        AssistantEditSyntaxValidator.SyntaxResult result =
                validator.check("proj-1", "turtle", List.of(new LineRangeSpliceWriter.SpliceEdit(1, 1, ":A :b :c @@@ .")));

        assertFalse(result.valid());
        assertTrue(result.detail().startsWith("The document would not parse as turtle after these edits: "),
                result.detail());
        assertTrue(result.detail().contains("[line 2]"), result.detail());
        assertFalse(Files.exists(spliced));
    }

    @Test
    void oboIsLeftToTheImportLikeTheOtherOwlApiFormats() throws Exception {
        AssistantEditSyntaxValidator.SyntaxResult result =
                validator.check("proj-1", "obo", List.of(new LineRangeSpliceWriter.SpliceEdit(1, 1, "[Term]")));

        assertTrue(result.valid());
        assertTrue(result.detail().contains("obo"));
        verify(spliceWriter, never()).splice(any(), anyString(), any());
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
