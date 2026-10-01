package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.service.AssistantEditProposalService.CheckResult;
import self.research.ontology.owlEditor.service.AssistantEditSemanticValidator.SemanticEdit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static self.research.ontology.owlEditor.service.AssistantGraphIdentifierLookup.OWL_CLASS;
import static self.research.ontology.owlEditor.service.AssistantGraphIdentifierLookup.OWL_DATATYPE_PROPERTY;
import static self.research.ontology.owlEditor.service.AssistantGraphIdentifierLookup.OWL_NAMED_INDIVIDUAL;
import static self.research.ontology.owlEditor.service.AssistantGraphIdentifierLookup.OWL_OBJECT_PROPERTY;

class AssistantEditSemanticValidatorTest {

    private static final String NS = "http://ex.org/pizza#";

    private static final String TURTLE_DOC = String.join("\n",
            "@prefix : <http://ex.org/pizza#> .",
            "@prefix owl: <http://www.w3.org/2002/07/owl#> .",
            "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .",
            ":Pizza a owl:Class .",
            ":Margherita a owl:Class ;",
            "    rdfs:subClassOf :Pizza ;",
            "    rdfs:label \"Margherita\" .",
            ":hasTopping a owl:ObjectProperty .",
            "@prefix : <http://other.org/food#> .",
            ":Soup a owl:Class .",
            "");

    @Mock
    private StorageManager storageManager;

    @Mock
    private SparqlDatasetService datasetService;

    @TempDir
    Path tempDir;

    private FakeAssistantGraph graph;

    private AssistantEditSemanticValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        graph = FakeAssistantGraph.installOn(datasetService);
        graph.declare(NS + "Pizza", OWL_CLASS);
        graph.declare(NS + "Margherita", OWL_CLASS);
        graph.declare(NS + "hasTopping", OWL_OBJECT_PROPERTY);
        graph.declare("http://other.org/food#Soup", OWL_CLASS);
        validator = new AssistantEditSemanticValidator(storageManager,
                new AssistantGraphIdentifierLookup(datasetService));
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(write("doc.ttl", TURTLE_DOC));
    }

    @Test
    void newReferenceToAnIdentifierThatIsNotInTheGraphFails() {
        List<CheckResult> checks = check("turtle", edit(5, "    rdfs:subClassOf :Pizza ;", "    rdfs:subClassOf :Piza ;"));

        CheckResult references = named(checks, "references_resolve");
        assertFalse(references.passed());
        assertTrue(references.detail().contains("<" + NS + "Piza>"), references.detail());
        assertEquals(List.of(NS + "Piza"), graph.lookedUp());
    }

    @Test
    void newReferenceToAnExistingIdentifierPasses() {
        List<CheckResult> checks = check("turtle",
                edit(5, "    rdfs:subClassOf :Pizza ;", "    rdfs:subClassOf :Pizza ; rdfs:seeAlso :hasTopping ;"));

        CheckResult references = named(checks, "references_resolve");
        assertTrue(references.passed(), references.detail());
        assertTrue(references.detail().contains("All 1 new identifier"), references.detail());
    }

    @Test
    void identifierDeclaredInTheSameGroupAndBuiltInsAreNotLookedUp() {
        List<CheckResult> checks = check("turtle",
                edit(3, ":Pizza a owl:Class .", ":Pizza a owl:Class .\n:Calzone a owl:Class ; rdfs:subClassOf :Pizza ."),
                edit(7, ":hasTopping a owl:ObjectProperty .",
                        ":hasTopping a owl:ObjectProperty ; rdfs:range :Calzone ; rdfs:comment \"x\"^^xsdNope:string ."));

        CheckResult references = named(checks, "references_resolve");
        assertFalse(references.passed());
        assertTrue(references.detail().contains("xsdNope:string"), references.detail());
        assertFalse(references.detail().contains("Calzone"), references.detail());
        assertTrue(graph.lookedUp().isEmpty());
    }

    @Test
    void builtInVocabularyNeverNeedsToExist() {
        List<CheckResult> checks = check("turtle", edit(3, ":Pizza a owl:Class .",
                ":Pizza a owl:Class ; rdfs:comment \"x\"^^<http://www.w3.org/2001/XMLSchema#string> ; owl:deprecated true ."));

        assertTrue(named(checks, "references_resolve").passed(), named(checks, "references_resolve").detail());
        assertTrue(graph.lookedUp().isEmpty());
    }

    @Test
    void prefixesAreResolvedPositionallyFromTheStreamedDocument() {
        List<CheckResult> checks = check("turtle", edit(9, ":Soup a owl:Class .", ":Soup a owl:Class ; rdfs:seeAlso :Stew ."));

        CheckResult references = named(checks, "references_resolve");
        assertFalse(references.passed());
        assertTrue(references.detail().contains("<http://other.org/food#Stew>"), references.detail());
        assertFalse(references.detail().contains(NS + "Stew"), references.detail());
    }

    @Test
    void predicateOnAContinuationLineIsNotMistakenForADeclaredSubject() {
        List<CheckResult> checks = check("turtle",
                edit(6, "    rdfs:label \"Margherita\" .", "    :hasTopingg :Pizza ."));

        CheckResult references = named(checks, "references_resolve");
        assertFalse(references.passed());
        assertTrue(references.detail().contains("<" + NS + "hasTopingg>"), references.detail());
    }

    @Test
    void retypingOnAContinuationLineIsCheckedAgainstTheSubjectFromAnEarlierLine() {
        List<CheckResult> checks = check("turtle",
                edit(5, "    rdfs:subClassOf :Pizza ;", "    a owl:DatatypeProperty ;"));

        CheckResult conflict = named(checks, "no_conflicting_declaration");
        assertFalse(conflict.passed());
        assertTrue(conflict.detail().contains("<" + NS + "Margherita> is declared here as owl:DatatypeProperty"),
                conflict.detail());
        assertTrue(conflict.detail().contains("owl:Class"), conflict.detail());
    }

    @Test
    void replacingADeclarationIsARetypeNotAConflict() {
        List<CheckResult> checks = check("turtle",
                edit(7, ":hasTopping a owl:ObjectProperty .", ":hasTopping a owl:DatatypeProperty ."));

        CheckResult conflict = named(checks, "no_conflicting_declaration");
        assertTrue(conflict.passed(), conflict.detail());
    }

    @Test
    void objectAndDatatypePropertyClashWithTheGraphFails() {
        List<CheckResult> checks = check("turtle",
                edit(3, ":Pizza a owl:Class .", ":Pizza a owl:Class .\n:hasTopping a owl:DatatypeProperty ."));

        CheckResult conflict = named(checks, "no_conflicting_declaration");
        assertFalse(conflict.passed());
        assertTrue(conflict.detail().contains("owl:DatatypeProperty"), conflict.detail());
        assertTrue(conflict.detail().contains("owl:ObjectProperty"), conflict.detail());
    }

    @Test
    void classAndIndividualPunningIsAllowed() {
        List<CheckResult> checks = check("turtle",
                edit(3, ":Pizza a owl:Class .", ":Pizza a owl:Class , owl:NamedIndividual ."));

        CheckResult conflict = named(checks, "no_conflicting_declaration");
        assertTrue(conflict.passed(), conflict.detail());
        assertTrue(DeclarationKinds.compatible(OWL_CLASS, OWL_NAMED_INDIVIDUAL));
        assertFalse(DeclarationKinds.compatible(OWL_CLASS, OWL_OBJECT_PROPERTY));
        assertFalse(DeclarationKinds.compatible(OWL_OBJECT_PROPERTY, OWL_DATATYPE_PROPERTY));
    }

    @Test
    void classAndPropertyDeclaredTogetherInTheGroupFails() {
        List<CheckResult> checks = check("turtle",
                edit(3, ":Pizza a owl:Class .", ":Pizza a owl:Class .\n:Fresh a owl:Class , owl:ObjectProperty ."));

        CheckResult conflict = named(checks, "no_conflicting_declaration");
        assertFalse(conflict.passed());
        assertTrue(conflict.detail().contains("declared in this group as both owl:Class and owl:ObjectProperty"),
                conflict.detail());
    }

    @Test
    void unchangedDeclarationsAreNotReChecked() {
        List<CheckResult> checks = check("turtle",
                edit(3, ":Pizza a owl:Class .", ":Pizza a owl:Class ; rdfs:label \"Pizza\" ."));

        CheckResult conflict = named(checks, "no_conflicting_declaration");
        assertTrue(conflict.passed());
        assertEquals("This group adds no OWL declarations.", conflict.detail());
    }

    @Test
    void atMostFiftyNewIdentifiersAreLookedUpAndTheRestAreCounted() {
        StringBuilder text = new StringBuilder(":Pizza a owl:Class ; rdfs:seeAlso ");
        for (int i = 0; i < 53; i++) {
            text.append(i == 0 ? "" : ", ").append(":missing").append(i);
        }
        text.append(" .");

        List<CheckResult> checks = check("turtle", edit(3, ":Pizza a owl:Class .", text.toString()));

        CheckResult references = named(checks, "references_resolve");
        assertFalse(references.passed());
        assertTrue(references.detail().contains("3 more new identifiers were not checked (limit 50 per group)"),
                references.detail());
        assertEquals(50, graph.lookedUp().size());
    }

    @Test
    void atMostFiftyNewDeclarationsAreCheckedAgainstTheGraph() {
        StringBuilder text = new StringBuilder(":Pizza a owl:Class .");
        for (int i = 0; i < 51; i++) {
            text.append("\n:Fresh").append(i).append(" a owl:Class .");
        }

        List<CheckResult> checks = check("turtle", edit(3, ":Pizza a owl:Class .", text.toString()));

        CheckResult conflict = named(checks, "no_conflicting_declaration");
        assertTrue(conflict.passed(), conflict.detail());
        assertTrue(conflict.detail().contains("1 more declared identifier was not checked"), conflict.detail());
    }

    @Test
    void identifiersIntroducedByTheOperationAreNotRequiredToExist() {
        List<CheckResult> checks = validator.check("proj-1", "turtle",
                List.of(edit(5, "    rdfs:subClassOf :Pizza ;", "    rdfs:subClassOf :Pie ;")), Set.of(NS + "Pie"));

        assertTrue(named(checks, "references_resolve").passed(), named(checks, "references_resolve").detail());
        assertTrue(graph.lookedUp().isEmpty());
    }

    @Test
    void graphFailureFailsBothChecksClosed() {
        graph.failure = new RuntimeException("GraphDB unavailable");

        List<CheckResult> checks = check("turtle",
                edit(3, ":Pizza a owl:Class .", ":Pizza a owl:Class .\n:Fresh a owl:Class ; rdfs:seeAlso :Nope ."));

        assertFalse(named(checks, "references_resolve").passed());
        assertTrue(named(checks, "references_resolve").detail().contains("GraphDB unavailable"));
        assertFalse(named(checks, "no_conflicting_declaration").passed());
    }

    @Test
    void unreadableDocumentFailsClosed() throws Exception {
        when(storageManager.ensureCodeViewFile("proj-1", "turtle")).thenReturn(tempDir.resolve("missing.ttl"));

        List<CheckResult> checks = check("turtle", edit(3, ":Pizza a owl:Class .", ":Pie a owl:Class ."));

        assertFalse(named(checks, "references_resolve").passed());
        assertFalse(named(checks, "no_conflicting_declaration").passed());
    }

    @Test
    void owlApiAndOtherUnscannedFormatsAreNotApplicableButPassed() throws Exception {
        for (String format : List.of("manchester", "functional", "owlxml", "obo", "jsonld")) {
            List<CheckResult> checks = check(format, edit(1, "Class: Pizza", "Class: Piza"));
            assertEquals(2, checks.size());
            for (CheckResult result : checks) {
                assertTrue(result.passed());
                assertTrue(result.detail().startsWith("Not applicable"), result.detail());
                assertTrue(result.detail().contains(format), result.detail());
            }
        }
        verify(storageManager, never()).ensureCodeViewFile(anyString(), org.mockito.ArgumentMatchers.eq("manchester"));
        assertTrue(graph.queries.isEmpty());
    }

    private static final String RDFXML_DOC = String.join("\n",
            "<?xml version=\"1.0\"?>",
            "<!DOCTYPE rdf:RDF [",
            "    <!ENTITY owl \"http://www.w3.org/2002/07/owl#\" >",
            "]>",
            "<rdf:RDF xmlns=\"http://ex.org/pizza#\"",
            "     xml:base=\"http://ex.org/pizza\"",
            "     xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"",
            "     xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\"",
            "     xmlns:owl=\"http://www.w3.org/2002/07/owl#\">",
            "    <owl:Class rdf:about=\"#Margherita\">",
            "        <rdfs:subClassOf rdf:resource=\"#Pizza\"/>",
            "    </owl:Class>",
            "</rdf:RDF>",
            "");

    @Test
    void rdfXmlPropertyElementInsideANodeIsCheckedInItsRealContext() throws Exception {
        when(storageManager.ensureCodeViewFile("proj-1", "rdfxml")).thenReturn(write("doc.owl", RDFXML_DOC));

        List<CheckResult> checks = check("rdfxml", edit(10,
                "        <rdfs:subClassOf rdf:resource=\"#Pizza\"/>",
                "        <rdfs:subClassOf rdf:resource=\"#Piza\"/>\n        <rdf:type rdf:resource=\"&owl;ObjectProperty\"/>"));

        CheckResult references = named(checks, "references_resolve");
        assertFalse(references.passed());
        assertTrue(references.detail().contains("<" + NS + "Piza>"), references.detail());
        CheckResult conflict = named(checks, "no_conflicting_declaration");
        assertFalse(conflict.passed());
        assertTrue(conflict.detail().contains("<" + NS + "Margherita> is declared here as owl:ObjectProperty"),
                conflict.detail());
    }

    @Test
    void rdfXmlNewNodeDeclaredInTheGroupCountsAsDeclared() throws Exception {
        when(storageManager.ensureCodeViewFile("proj-1", "rdfxml")).thenReturn(write("doc.owl", RDFXML_DOC));

        List<CheckResult> checks = check("rdfxml", edit(12, "</rdf:RDF>", String.join("\n",
                "    <owl:Class rdf:about=\"#Calzone\">",
                "        <rdfs:subClassOf rdf:resource=\"#Pizza\"/>",
                "    </owl:Class>",
                "    <Pizza rdf:about=\"#myPizza\"/>",
                "</rdf:RDF>")));

        assertTrue(named(checks, "references_resolve").passed(), named(checks, "references_resolve").detail());
        assertTrue(named(checks, "no_conflicting_declaration").passed(),
                named(checks, "no_conflicting_declaration").detail());
    }

    private List<CheckResult> check(String format, SemanticEdit... edits) {
        return validator.check("proj-1", format, new ArrayList<>(List.of(edits)), Set.of());
    }

    private SemanticEdit edit(long line, String original, String replacement) {
        return new SemanticEdit(line, (int) original.lines().count(), original, replacement);
    }

    private CheckResult named(List<CheckResult> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    private Path write(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static final class DeclarationKinds {
        static boolean compatible(String a, String b) {
            return !AssistantEditSemanticValidator.incompatible(a, b);
        }
    }
}
