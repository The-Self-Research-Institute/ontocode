package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.service.AssistantEditReferenceCoverageValidator.CoverageEdit;
import self.research.ontology.owlEditor.service.AssistantEditReferenceCoverageValidator.CoverageResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class AssistantEditReferenceCoverageValidatorTest {

    @Mock
    private StorageManager storageManager;

    private AssistantEditReferenceCoverageValidator validator;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        validator = new AssistantEditReferenceCoverageValidator(storageManager);
    }

    private void seedDocument(String content) throws IOException {
        Path file = tempDir.resolve("ontology.ttl");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        when(storageManager.ensureCodeViewFile(anyString(), anyString())).thenReturn(file);
    }

    @Test
    void predicatesOnContinuationLinesAreNotTreatedAsRemovedSubjects() throws Exception {
        String restOfDocument = String.join("\n",
                "<http://example.com/onto#SomePizza> a owl:Class;",
                "  rdfs:label \"SomePizza\";",
                "  owl:disjointWith :SomeOtherPizza .",
                "");
        seedDocument(restOfDocument);

        String original = String.join("\n",
                "<http://example.com/onto#VegetarianPizza> a owl:Class;",
                "  rdfs:label \"VegetarianPizza\";",
                "  rdfs:subClassOf :Pizza;",
                "  owl:disjointUnionOf _:genid1;",
                "  owl:disjointWith :MeatTopping;",
                "  owl:hasKey _:genid2, _:genid3 .");
        String replacement = String.join("\n",
                "<http://example.com/onto#VegetarianPizza> a owl:Class;",
                "  rdfs:label \"VegetarianPizza\";",
                "  rdfs:subClassOf :Pizza .");

        CoverageEdit edit = new CoverageEdit(100, 6, original, replacement);
        CoverageResult result = validator.check("proj-1", "turtle", List.of(edit));

        assertTrue(result.covered(), "predicates on continuation lines must not be mistaken for removed subjects: "
                + result.detail());
    }

    @Test
    void removingAnEntityStillReferencedElsewhereIsStillCaught() throws Exception {
        String restOfDocument = String.join("\n",
                "<http://example.com/onto#OtherPizza> a owl:Class;",
                "  rdfs:subClassOf <http://example.com/onto#VegetarianPizza> .",
                "");
        seedDocument(restOfDocument);

        String original = String.join("\n",
                "<http://example.com/onto#VegetarianPizza> a owl:Class;",
                "  rdfs:label \"VegetarianPizza\" .");
        String replacement = "";

        CoverageEdit edit = new CoverageEdit(100, 2, original, replacement);
        CoverageResult result = validator.check("proj-1", "turtle", List.of(edit));

        assertFalse(result.covered());
        assertTrue(result.detail().contains("http://example.com/onto#VegetarianPizza"));
    }
}
