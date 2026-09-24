package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditOperation;
import self.research.ontology.owlEditor.service.AssistantRenameService.DerivedEdit;
import self.research.ontology.owlEditor.service.AssistantRenameService.RenameDerivation;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantRenameServiceTest {

    private static final String NS = "http://ex.org/pizza#";

    @Mock
    private StorageManager storageManager;

    @Mock
    private SparqlDatasetService datasetService;

    @TempDir
    Path tempDir;

    private AssistantRenameService renameService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        renameService = new AssistantRenameService(storageManager, new AssistantGraphIdentifierLookup(datasetService));
        when(datasetService.execSelectCapped(anyString(), anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new SparqlDatasetService.CappedSparqlResult(List.of("x"), List.of(), false, null));
    }

    private static final String TURTLE_DOC = String.join("\n",
            "@prefix : <http://ex.org/pizza#> .",
            "@prefix pz: <http://ex.org/pizza#> .",
            "@prefix owl: <http://www.w3.org/2002/07/owl#> .",
            "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .",
            "",
            ":Pizza a owl:Class .",
            "pz:Margherita rdfs:subClassOf :Pizza .",
            "<http://ex.org/pizza#Pizza> rdfs:label \"Pizza\" .",
            ":PizzaTopping a owl:Class .",
            ":X rdfs:comment \"mentions :Pizza and <http://ex.org/pizza#Pizza>\" .",
            "# :Pizza in a comment",
            ":Y rdfs:seeAlso :Pizza, pz:Pizza ; rdfs:comment \"\"\"a long",
            ":Pizza text that spans lines\"\"\" .",
            ":Z rdfs:seeAlso :Pizza.",
            "");

    @Test
    void turtleRenameMatchesEveryFormOfTheIdentifierAndNothingElse() throws Exception {
        Path doc = write("doc.ttl", TURTLE_DOC);
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(doc);

        RenameDerivation result = renameService.derive("proj-1", rename("turtle", ":Pizza", ":Pie"), 5000);

        assertTrue(result.ok(), result.detail());
        assertEquals(NS + "Pizza", result.targetIri());
        assertEquals(NS + "Pie", result.replacementIri());
        assertEquals(6, result.occurrences());
        assertEquals(List.of(5L, 6L, 7L, 11L, 13L), result.edits().stream().map(DerivedEdit::line).toList());
        assertEquals(":Pie a owl:Class .", result.edits().get(0).newText());
        assertEquals("pz:Margherita rdfs:subClassOf :Pie .", result.edits().get(1).newText());
        assertEquals("<http://ex.org/pizza#Pie> rdfs:label \"Pizza\" .", result.edits().get(2).newText());
        assertEquals(":Y rdfs:seeAlso :Pie, pz:Pie ; rdfs:comment \"\"\"a long", result.edits().get(3).newText());
        assertEquals(":Z rdfs:seeAlso :Pie.", result.edits().get(4).newText());
        assertTrue(result.detail().contains("6 occurrences"));
        assertTrue(result.detail().contains("5 lines"));

        assertRenamedGraphIsIsomorphic(TURTLE_DOC, result, RDFFormat.TURTLE, NS + "Pizza", NS + "Pie");
    }

    @Test
    void replacementOutsideEveryDeclaredNamespaceIsWrittenAsAFullIri() throws Exception {
        Path doc = write("doc.ttl", TURTLE_DOC);
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(doc);

        RenameDerivation result = renameService.derive("proj-1",
                rename("turtle", "<http://ex.org/pizza#Pizza>", "<http://other.org/food/Pie>"), 5000);

        assertTrue(result.ok(), result.detail());
        assertEquals("<http://other.org/food/Pie> a owl:Class .", result.edits().get(0).newText());
        assertRenamedGraphIsIsomorphic(TURTLE_DOC, result, RDFFormat.TURTLE, NS + "Pizza", "http://other.org/food/Pie");
    }

    @Test
    void baseRelativeIrisAreResolvedExactly() throws Exception {
        String content = String.join("\n",
                "@base <http://ex.org/pizza> .",
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .",
                "<#Pizza> a owl:Class .",
                "<pizza#Pizza> a owl:Class .",
                "<other#Pizza> a owl:Class .");
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(write("base.ttl", content));

        RenameDerivation result = renameService.derive("proj-1",
                rename("turtle", "<http://ex.org/pizza#Pizza>", "<http://ex.org/pizza#Pie>"), 5000);

        assertTrue(result.ok(), result.detail());
        assertEquals(2, result.occurrences());
        assertEquals("<http://ex.org/pizza#Pie> a owl:Class .", result.edits().get(0).newText());
        assertEquals(3L, result.edits().get(1).line());
    }

    @Test
    void relativeIriWithoutABaseFailsClosed() throws Exception {
        String content = "@prefix : <http://ex.org/pizza#> .\n:Pizza <seeAlso> <Other> .\n";
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(write("rel.ttl", content));

        RenameDerivation result = renameService.derive("proj-1", rename("turtle", ":Pizza", ":Pie"), 5000);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("relative IRI"));
        assertTrue(result.edits().isEmpty());
    }

    @Test
    void prefixRedefinedMidDocumentIsHonouredPositionally() throws Exception {
        String content = String.join("\n",
                "@prefix ex: <http://a.org/> .",
                "ex:Foo ex:p ex:Bar .",
                "@prefix ex: <http://b.org/> .",
                "ex:Foo ex:p ex:Bar .",
                "<http://a.org/Foo> ex:q ex:Foo .");
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(write("redef.ttl", content));

        RenameDerivation result = renameService.derive("proj-1",
                rename("turtle", "<http://a.org/Foo>", "<http://a.org/Renamed>"), 5000);

        assertTrue(result.ok(), result.detail());
        assertEquals(List.of(1L, 4L), result.edits().stream().map(DerivedEdit::line).toList());
        assertEquals("ex:Renamed ex:p ex:Bar .", result.edits().get(0).newText());
        assertEquals("<http://a.org/Renamed> ex:q ex:Foo .", result.edits().get(1).newText());

        RenameDerivation ambiguous = renameService.derive("proj-1", rename("turtle", "ex:Foo", "ex:Renamed"), 5000);
        assertFalse(ambiguous.ok());
        assertTrue(ambiguous.detail().contains("more than one namespace"));
    }

    @Test
    void nTriplesRenameAlwaysWritesFullIris() throws Exception {
        String content = String.join("\n",
                "<http://ex.org/pizza#Pizza> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/2002/07/owl#Class> .",
                "<http://ex.org/pizza#A> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://ex.org/pizza#Pizza> .",
                "<http://ex.org/pizza#B> <http://www.w3.org/2000/01/rdf-schema#label> \"<http://ex.org/pizza#Pizza>\" .");
        when(storageManager.ensureCodeViewFile("proj-1", "ntriples")).thenReturn(write("doc.nt", content));

        RenameDerivation result = renameService.derive("proj-1",
                rename("ntriples", "http://ex.org/pizza#Pizza", "http://ex.org/pizza#Pie"), 5000);

        assertTrue(result.ok(), result.detail());
        assertEquals(2, result.occurrences());
        assertRenamedGraphIsIsomorphic(content, result, RDFFormat.NTRIPLES, NS + "Pizza", NS + "Pie");
    }

    @Test
    void replacementAlreadyInTheDocumentIsRefused() throws Exception {
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(write("doc.ttl", TURTLE_DOC));

        RenameDerivation result = renameService.derive("proj-1", rename("turtle", ":Pizza", ":Margherita"), 5000);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("already appears in the document"));
        assertTrue(result.edits().isEmpty());
    }

    @Test
    void replacementAlreadyInTheGraphIsRefused() throws Exception {
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(write("doc.ttl", TURTLE_DOC));
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        when(datasetService.execSelectCapped(eq("proj-1"), query.capture(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new SparqlDatasetService.CappedSparqlResult(List.of("x"),
                        List.of(Map.of("x", NS + "Pie")), false, null));

        RenameDerivation result = renameService.derive("proj-1", rename("turtle", ":Pizza", ":Pie"), 5000);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("already exists in the graph"));
        assertTrue(query.getValue().contains("<" + NS + "Pie>"));
    }

    @Test
    void graphLookupFailureFailsClosed() throws Exception {
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(write("doc.ttl", TURTLE_DOC));
        when(datasetService.execSelectCapped(anyString(), anyString(), anyInt(), anyInt(), anyLong()))
                .thenThrow(new RuntimeException("GraphDB unavailable"));

        RenameDerivation result = renameService.derive("proj-1", rename("turtle", ":Pizza", ":Pie"), 5000);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("GraphDB unavailable"));
    }

    @Test
    void malformedIdentifiersAreRefused() throws Exception {
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(write("doc.ttl", TURTLE_DOC));

        for (String[] pair : List.of(
                new String[]{"", ":Pie"},
                new String[]{":Pizza", "  "},
                new String[]{"<http://ex.org/pizza#Pizza", ":Pie"},
                new String[]{":Pizza", "<http://ex.org/has space>"},
                new String[]{":Pizza", "zz:Pie"},
                new String[]{":Pizza", "Pie"},
                new String[]{":Pizza", "owl:Thing2"},
                new String[]{":Pizza", ":Pizza"},
                new String[]{":Pizza", ":Bad,Name"})) {
            RenameDerivation result = renameService.derive("proj-1", rename("turtle", pair[0], pair[1]), 5000);
            assertFalse(result.ok(), "expected refusal for " + Arrays.toString(pair));
            assertTrue(result.edits().isEmpty());
        }
    }

    @Test
    void owlApiFormatsFailClosedNamingTheSupportedFormats() throws Exception {
        for (String format : List.of("manchester", "functional", "owlxml", "jsonld")) {
            RenameDerivation result = renameService.derive("proj-1", rename(format, ":Pizza", ":Pie"), 5000);
            assertFalse(result.ok());
            assertTrue(result.detail().contains("turtle, ntriples and rdfxml"), result.detail());
        }
        verify(storageManager, never()).ensureCodeViewFile(anyString(), anyString());
    }

    @Test
    void unsupportedOperationTypeIsRefused() {
        RenameDerivation result = renameService.derive("proj-1",
                new EditOperation("delete_identifier", "turtle", ":Pizza", ":Pie"), 5000);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("rename_identifier"));
    }

    @Test
    void renameOverTheLineCapIsRefusedRatherThanTruncated() throws Exception {
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(write("doc.ttl", TURTLE_DOC));

        RenameDerivation result = renameService.derive("proj-1", rename("turtle", ":Pizza", ":Pie"), 3);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("more than 3 lines"));
        assertTrue(result.edits().isEmpty());
    }

    @Test
    void targetThatDoesNotOccurIsRefused() throws Exception {
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(write("doc.ttl", TURTLE_DOC));

        RenameDerivation result = renameService.derive("proj-1", rename("turtle", ":Calzone", ":Pie"), 5000);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("does not occur"));
    }

    private static final String RDFXML_DOC = String.join("\n",
            "<?xml version=\"1.0\"?>",
            "<!DOCTYPE rdf:RDF [",
            "    <!ENTITY pizza \"http://ex.org/pizza#\" >",
            "    <!ENTITY owl \"http://www.w3.org/2002/07/owl#\" >",
            "]>",
            "<rdf:RDF xmlns=\"http://ex.org/pizza#\"",
            "     xml:base=\"http://ex.org/pizza\"",
            "     xmlns:pizza=\"http://ex.org/pizza#\"",
            "     xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"",
            "     xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\"",
            "     xmlns:owl=\"http://www.w3.org/2002/07/owl#\">",
            "    <owl:Class rdf:about=\"http://ex.org/pizza#Pizza\"/>",
            "    <owl:Class rdf:about=\"&pizza;Margherita\">",
            "        <rdfs:subClassOf rdf:resource=\"#Pizza\"/>",
            "        <rdfs:comment>not http://ex.org/pizza#Pizza</rdfs:comment>",
            "    </owl:Class>",
            "    <owl:ObjectProperty rdf:about=\"http://ex.org/pizza#hasTopping\"/>",
            "    <pizza:Pizza rdf:about=\"http://ex.org/pizza#myPizza\">",
            "        <pizza:hasTopping rdf:resource=\"http://ex.org/pizza#Cheese\"/>",
            "        <hasTopping rdf:resource=\"http://ex.org/pizza#Tomato\"/>",
            "    </pizza:Pizza>",
            "    <owl:NamedIndividual rdf:ID=\"Cheese\"/>",
            "    <owl:NamedIndividual rdf:about=\"http://ex.org/pizza#Tomato\"/>",
            "    <owl:Class",
            "        rdf:about=\"http://ex.org/pizza#Calzone\">",
            "        <rdfs:subClassOf rdf:resource=\"http://ex.org/pizza#Pizza\"/>",
            "    </owl:Class>",
            "    <!-- rdf:about=\"http://ex.org/pizza#Pizza\" in a comment",
            "         <pizza:Pizza rdf:about=\"x\"/> -->",
            "</rdf:RDF>",
            "");

    @Test
    void rdfXmlRenameRewritesAttributeValuesAndElementNames() throws Exception {
        when(storageManager.ensureCodeViewFile("proj-1", "rdfxml")).thenReturn(write("doc.owl", RDFXML_DOC));

        RenameDerivation result = renameService.derive("proj-1", rename("rdfxml", "pizza:Pizza", "pizza:Pie"), 5000);

        assertTrue(result.ok(), result.detail());
        assertEquals(List.of(11L, 13L, 17L, 20L, 25L), result.edits().stream().map(DerivedEdit::line).toList());
        assertEquals("    <owl:Class rdf:about=\"http://ex.org/pizza#Pie\"/>", result.edits().get(0).newText());
        assertEquals("        <rdfs:subClassOf rdf:resource=\"http://ex.org/pizza#Pie\"/>", result.edits().get(1).newText());
        assertEquals("    <pizza:Pie rdf:about=\"http://ex.org/pizza#myPizza\">", result.edits().get(2).newText());
        assertEquals("    </pizza:Pie>", result.edits().get(3).newText());
        assertEquals(5, result.occurrences());

        assertRenamedGraphIsIsomorphic(RDFXML_DOC, result, RDFFormat.RDFXML, NS + "Pizza", NS + "Pie");
    }

    @Test
    void rdfXmlPropertyRenameRewritesPrefixedAndDefaultNamespaceElementNames() throws Exception {
        when(storageManager.ensureCodeViewFile("proj-1", "rdfxml")).thenReturn(write("doc.owl", RDFXML_DOC));

        RenameDerivation result = renameService.derive("proj-1",
                rename("rdfxml", "<http://ex.org/pizza#hasTopping>", "<http://ex.org/pizza#hasIngredient>"), 5000);

        assertTrue(result.ok(), result.detail());
        assertEquals(3, result.occurrences());
        assertEquals("        <pizza:hasIngredient rdf:resource=\"http://ex.org/pizza#Cheese\"/>",
                result.edits().get(1).newText());
        assertEquals("        <hasIngredient rdf:resource=\"http://ex.org/pizza#Tomato\"/>",
                result.edits().get(2).newText());
        assertRenamedGraphIsIsomorphic(RDFXML_DOC, result, RDFFormat.RDFXML, NS + "hasTopping", NS + "hasIngredient");
    }

    @Test
    void rdfXmlPropertyRenameIntoAnUndeclaredNamespaceFailsClosed() throws Exception {
        when(storageManager.ensureCodeViewFile("proj-1", "rdfxml")).thenReturn(write("doc.owl", RDFXML_DOC));

        RenameDerivation result = renameService.derive("proj-1",
                rename("rdfxml", "pizza:hasTopping", "<http://other.org/vocab#hasIngredient>"), 5000);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("qualified name"));
        assertTrue(result.edits().isEmpty());
    }

    @Test
    void rdfXmlIdDeclarationIsRenamedAsAnId() throws Exception {
        when(storageManager.ensureCodeViewFile("proj-1", "rdfxml")).thenReturn(write("doc.owl", RDFXML_DOC));

        RenameDerivation result = renameService.derive("proj-1", rename("rdfxml", "pizza:Cheese", "pizza:Gouda"), 5000);

        assertTrue(result.ok(), result.detail());
        assertEquals("    <owl:NamedIndividual rdf:ID=\"Gouda\"/>", result.edits().get(1).newText());
        assertRenamedGraphIsIsomorphic(RDFXML_DOC, result, RDFFormat.RDFXML, NS + "Cheese", NS + "Gouda");
    }

    @Test
    void rdfXmlTagSpanningLinesIsRewrittenOnTheRightLine() throws Exception {
        when(storageManager.ensureCodeViewFile("proj-1", "rdfxml")).thenReturn(write("doc.owl", RDFXML_DOC));

        RenameDerivation result = renameService.derive("proj-1", rename("rdfxml", "pizza:Calzone", "pizza:Folded"), 5000);

        assertTrue(result.ok(), result.detail());
        assertEquals(1, result.edits().size());
        assertEquals(24L, result.edits().get(0).line());
        assertEquals("        rdf:about=\"http://ex.org/pizza#Folded\">", result.edits().get(0).newText());
    }

    @Test
    void rdfXmlReplacementAlreadyPresentIsRefused() throws Exception {
        when(storageManager.ensureCodeViewFile("proj-1", "rdfxml")).thenReturn(write("doc.owl", RDFXML_DOC));

        RenameDerivation result = renameService.derive("proj-1", rename("rdfxml", "pizza:Pizza", "pizza:Tomato"), 5000);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("already appears"));
    }

    @Test
    void prefixRedefinedLaterOnTheSameLineKeepsTheNamespaceTheTokenWasWrittenIn() throws Exception {
        String content = String.join("\n",
                "@prefix ex: <http://a.org/> .",
                "ex:Foo a ex:C . @prefix ex: <http://a.org/sub/> . ex:Other a ex:C .");
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(write("sameline.ttl", content));

        RenameDerivation result = renameService.derive("proj-1",
                rename("turtle", "<http://a.org/Foo>", "<http://a.org/sub/Renamed>"), 5000);

        assertTrue(result.ok(), result.detail());
        assertEquals("<http://a.org/sub/Renamed> a ex:C . @prefix ex: <http://a.org/sub/> . ex:Other a ex:C .",
                result.edits().get(0).newText());
        assertRenamedGraphIsIsomorphic(content, result, RDFFormat.TURTLE, "http://a.org/Foo", "http://a.org/sub/Renamed");
    }

    @Test
    void unicodeEscapedIriAndDatatypePositionAreMatchedExactly() throws Exception {
        String content = String.join("\n",
                "@prefix : <http://ex.org/pizza#> .",
                "<http://ex.org/pizza#Pi\\u007Aza> :p \"1\"^^:Pizza .",
                ":q :r ( :Pizza :PizzaBase ) .");
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(write("esc.ttl", content));

        RenameDerivation result = renameService.derive("proj-1", rename("turtle", ":Pizza", ":Pie"), 5000);

        assertTrue(result.ok(), result.detail());
        assertEquals(3, result.occurrences());
        assertEquals("<http://ex.org/pizza#Pie> :p \"1\"^^:Pie .", result.edits().get(0).newText());
        assertEquals(":q :r ( :Pie :PizzaBase ) .", result.edits().get(1).newText());

        Model before = Rio.parse(new StringReader(content), "", RDFFormat.TURTLE);
        Model after = Rio.parse(new StringReader(applyEdits(content, result.edits())), "", RDFFormat.TURTLE);
        assertEquals(before.size(), after.size());
        for (Statement st : after) {
            boolean datatypeLeft = st.getObject().isLiteral()
                    && ((org.eclipse.rdf4j.model.Literal) st.getObject()).getDatatype().stringValue().equals(NS + "Pizza");
            assertFalse(datatypeLeft || st.getSubject().stringValue().equals(NS + "Pizza")
                    || st.getObject().stringValue().equals(NS + "Pizza"), "left behind: " + st);
        }
        assertTrue(after.stream().anyMatch(st -> st.getObject().isLiteral()
                && ((org.eclipse.rdf4j.model.Literal) st.getObject()).getDatatype().stringValue().equals(NS + "Pie")));
        assertTrue(after.stream().anyMatch(st -> st.getSubject().stringValue().equals(NS + "Pie")));
    }

    @Test
    void rdfXmlIdIsRenamedWhenTheBaseAlreadyEndsInAHash() throws Exception {
        String content = String.join("\n",
                "<?xml version=\"1.0\"?>",
                "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"",
                "     xmlns:owl=\"http://www.w3.org/2002/07/owl#\"",
                "     xmlns:pizza=\"http://ex.org/pizza#\"",
                "     xml:base=\"http://ex.org/pizza#\">",
                "    <owl:NamedIndividual rdf:ID=\"Cheese\"/>",
                "    <owl:NamedIndividual rdf:about=\"#Tomato\">",
                "        <pizza:with rdf:resource=\"#Cheese\"/>",
                "    </owl:NamedIndividual>",
                "</rdf:RDF>");
        when(storageManager.ensureCodeViewFile("proj-1", "rdfxml")).thenReturn(write("hashbase.owl", content));

        RenameDerivation result = renameService.derive("proj-1", rename("rdfxml", "pizza:Cheese", "pizza:Gouda"), 5000);

        assertTrue(result.ok(), result.detail());
        assertEquals("    <owl:NamedIndividual rdf:ID=\"Gouda\"/>", result.edits().get(0).newText());
        assertEquals("        <pizza:with rdf:resource=\"http://ex.org/pizza#Gouda\"/>", result.edits().get(1).newText());
        assertRenamedGraphIsIsomorphic(content, result, RDFFormat.RDFXML, NS + "Cheese", NS + "Gouda");
    }

    @Test
    void rdfXmlIdCannotBeRenamedOutsideItsBaseAndNothingIsGenerated() throws Exception {
        when(storageManager.ensureCodeViewFile("proj-1", "rdfxml")).thenReturn(write("doc.owl", RDFXML_DOC));

        RenameDerivation result = renameService.derive("proj-1",
                rename("rdfxml", "pizza:Cheese", "<http://other.org/food#Gouda>"), 5000);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("rdf:ID"), result.detail());
        assertTrue(result.edits().isEmpty());
    }

    @Test
    void rdfXmlNestedXmlBaseResolvesRelativeValuesAgainstTheElementBase() throws Exception {
        String content = String.join("\n",
                "<?xml version=\"1.0\"?>",
                "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"",
                "     xmlns:owl=\"http://www.w3.org/2002/07/owl#\"",
                "     xml:base=\"http://ex.org/pizza\">",
                "    <owl:Class rdf:about=\"#Pizza\"/>",
                "    <owl:Class xml:base=\"http://other.org/food\" rdf:about=\"#Pizza\"/>",
                "</rdf:RDF>");
        when(storageManager.ensureCodeViewFile("proj-1", "rdfxml")).thenReturn(write("nested.owl", content));

        RenameDerivation result = renameService.derive("proj-1",
                rename("rdfxml", "<http://ex.org/pizza#Pizza>", "<http://ex.org/pizza#Pie>"), 5000);

        assertTrue(result.ok(), result.detail());
        assertEquals(1, result.occurrences());
        assertEquals(4L, result.edits().get(0).line());
        assertRenamedGraphIsIsomorphic(content, result, RDFFormat.RDFXML, NS + "Pizza", NS + "Pie");
    }

    @Test
    void rdfXmlDocumentEndingInsideATagIsRefused() throws Exception {
        String content = String.join("\n",
                "<?xml version=\"1.0\"?>",
                "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"",
                "     xmlns:owl=\"http://www.w3.org/2002/07/owl#\">",
                "    <owl:Class rdf:about=\"http://ex.org/pizza#Pizza\"/>",
                "    <owl:Class rdf:about=\"http://ex.org/pizza#Pizza\"");
        when(storageManager.ensureCodeViewFile("proj-1", "rdfxml")).thenReturn(write("cut.owl", content));

        RenameDerivation result = renameService.derive("proj-1",
                rename("rdfxml", "<http://ex.org/pizza#Pizza>", "<http://ex.org/pizza#Pie>"), 5000);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("unclosed tag"), result.detail());
        assertTrue(result.edits().isEmpty());
    }

    @Test
    void undeclaredPrefixAnywhereInTheDocumentFailsClosed() throws Exception {
        String content = "@prefix : <http://ex.org/pizza#> .\n:Pizza :p zz:Other .\n";
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(write("undeclared.ttl", content));

        RenameDerivation result = renameService.derive("proj-1", rename("turtle", ":Pizza", ":Pie"), 5000);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("undeclared prefix"), result.detail());
    }

    private EditOperation rename(String targetPath, String target, String replacement) {
        return new EditOperation("rename_identifier", targetPath, target, replacement);
    }

    private Path write(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    static String applyEdits(String content, List<DerivedEdit> edits) {
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));
        for (DerivedEdit edit : edits) {
            assertEquals(lines.get((int) edit.line()), edit.originalText());
            lines.set((int) edit.line(), edit.newText());
        }
        return String.join("\n", lines);
    }

    private void assertRenamedGraphIsIsomorphic(String original, RenameDerivation result, RDFFormat format,
                                                String from, String to) throws Exception {
        Model before = Rio.parse(new StringReader(original), "", format);
        Model after = Rio.parse(new StringReader(applyEdits(original, result.edits())), "", format);
        Model expected = new LinkedHashModel();
        SimpleValueFactory vf = SimpleValueFactory.getInstance();
        IRI fromIri = vf.createIRI(from);
        IRI toIri = vf.createIRI(to);
        boolean sawTarget = false;
        for (Statement st : before) {
            sawTarget |= st.getSubject().equals(fromIri) || st.getPredicate().equals(fromIri) || st.getObject().equals(fromIri);
            Resource s = st.getSubject().equals(fromIri) ? toIri : st.getSubject();
            IRI p = st.getPredicate().equals(fromIri) ? toIri : st.getPredicate();
            Value o = st.getObject().equals(fromIri) ? toIri : st.getObject();
            expected.add(s, p, o);
        }
        assertTrue(sawTarget);
        assertTrue(Models.isomorphic(expected, after), "renamed graph differs from the expected substitution");
    }
}
