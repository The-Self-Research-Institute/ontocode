package self.research.ontology.swrl.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLRule;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.swrlapi.core.SWRLRuleEngine;
import org.swrlapi.factory.SWRLAPIFactory;
import self.research.ontology.swrl.model.ExecutionResult;
import self.research.ontology.swrl.model.ValidationResult;
import self.research.ontology.swrl.repository.SwrlRuleRepository;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PizzaEditorExportTest {

    private static final String NEW_RULE = "Pizza(?p) ^ hasTopping(?p, ?t) ^ MeatTopping(?t) -> MeatyPizzaByRule(?p)";

    private HttpServer server;
    private final AtomicReference<byte[]> body = new AtomicReference<>();
    private final AtomicReference<String> requested = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        serve("/pizza_editor_export.rdf");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requested.set(exchange.getRequestURI().toString());
            byte[] bytes = body.get();
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void serve(String resource) throws Exception {
        body.set(text(resource).getBytes(StandardCharsets.UTF_8));
    }

    private static String text(String resource) throws Exception {
        try (var in = PizzaEditorExportTest.class.getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void configure(SwrlEngineService service, OntologyClientService client) {
        ReflectionTestUtils.setField(service, "ontologyClient", client);
        ReflectionTestUtils.setField(service, "ruleRepository", Mockito.mock(SwrlRuleRepository.class));
        ReflectionTestUtils.setField(service, "maxInferredAxioms", 1000);
        ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 60);
    }

    private OntologyClientService client() {
        return new OntologyClientService(new RestTemplateBuilder(), "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private SwrlEngineService service() {
        SwrlEngineService service = new SwrlEngineService();
        configure(service, client());
        return service;
    }

    private static OWLOntology parsedWithoutNormalizing(String document) {
        try {
            return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(document));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SwrlEngineService serviceServing(Supplier<OWLOntology> ontologies) throws Exception {
        OntologyClientService client = Mockito.mock(OntologyClientService.class);
        Mockito.when(client.fetchOntology(Mockito.anyString())).thenAnswer(i -> ontologies.get());
        Mockito.when(client.fetchOntologyWithStamp(Mockito.anyString(), Mockito.any()))
                .thenAnswer(i -> new OntologyClientService.FetchedOntology(ontologies.get(), 1L, null));
        SwrlEngineService service = new SwrlEngineService();
        configure(service, client);
        return service;
    }

    private static SwrlEngineService serviceWithoutNormalizing() throws Exception {
        String document = text("/pizza_editor_export.rdf");
        return serviceServing(() -> parsedWithoutNormalizing(document));
    }

    private static List<OWLLiteral> ssnValues(OWLOntology ontology) {
        return ontology.getAxioms(AxiomType.DATA_PROPERTY_ASSERTION).stream()
                .filter(axiom -> axiom.getProperty().toString().contains("#ssn"))
                .map(OWLDataPropertyAssertionAxiom::getObject).toList();
    }

    @Test
    void theRuleServiceParserReadsTheEditorsPlainStringsAsPlainLiteralsAndTheEngineThenFailsOnThem() throws Exception {
        OWLOntology raw = parsedWithoutNormalizing(text("/pizza_editor_export.rdf"));

        List<OWLLiteral> ssn = ssnValues(raw);
        assertEquals(4, ssn.size());
        assertTrue(ssn.stream().allMatch(OWLLiteral::isRDFPlainLiteral), ssn.toString());

        SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(raw);
        Exception failure = assertThrows(Exception.class, engine::infer);
        String message = String.valueOf(failure.getMessage());
        assertTrue(message.contains("MultipleSSNRule") && message.contains("PlainLiteral"), message);
    }

    @Test
    void theEditorsRdfXmlExportIsFetchedWithoutPlainLiteralsLeftInTheOntology() throws Exception {
        OWLOntology ontology = client().fetchOntologyWithStamp("p1", null).ontology();

        List<OWLLiteral> ssn = ssnValues(ontology);
        assertEquals(4, ssn.size());
        assertTrue(ssn.stream().noneMatch(OWLLiteral::isRDFPlainLiteral), ssn.toString());
        assertTrue(ssn.stream().allMatch(literal -> literal.getDatatype().isString()), ssn.toString());
    }

    @Test
    void theInternalTurtleExportAndTheDesktopTurtleExportAreNormalizedToo() throws Exception {
        for (String resource : List.of("/pizza_editor_export.ttl", "/pizza_desktop_export.ttl")) {
            serve(resource);
            OWLOntology ontology = client().fetchOntologyWithStamp("p1", null).ontology();

            List<OWLLiteral> ssn = ssnValues(ontology);
            assertEquals(4, ssn.size(), resource);
            assertTrue(ssn.stream().allMatch(literal -> literal.getDatatype().isString() && !literal.isRDFPlainLiteral()),
                    resource + " " + ssn);
            assertEquals(8, ontology.getAxioms(AxiomType.SWRL_RULE).size(), resource);
        }
    }

    @Test
    void literalsWithALanguageTagKeepTheirLanguage() throws Exception {
        OWLOntology ontology = parsedWithoutNormalizing(text("/pizza_editor_export.rdf"));
        var df = ontology.getOWLOntologyManager().getOWLDataFactory();
        var property = df.getOWLDataProperty(IRI.create("urn:t#label"));
        var subject = df.getOWLNamedIndividual(IRI.create("urn:t#a"));
        ontology.getOWLOntologyManager().addAxiom(ontology, df.getOWLDataPropertyAssertionAxiom(property, subject, df.getOWLLiteral("pizza", "it")));
        ontology.getOWLOntologyManager().addAxiom(ontology, df.getOWLDataPropertyAssertionAxiom(property, subject, df.getOWLLiteral("plain", "")));

        PlainLiteralNormalizer.apply(ontology);

        List<OWLLiteral> labels = ontology.getAxioms(AxiomType.DATA_PROPERTY_ASSERTION).stream()
                .filter(axiom -> axiom.getProperty().equals(property)).map(OWLDataPropertyAssertionAxiom::getObject).toList();
        assertEquals(2, labels.size());
        assertTrue(labels.stream().anyMatch(literal -> literal.hasLang("it") && literal.getLiteral().equals("pizza")), labels.toString());
        assertTrue(labels.stream().anyMatch(literal -> literal.getDatatype().isString() && literal.getLiteral().equals("plain")), labels.toString());
    }

    @Test
    void theEightEmbeddedRulesAreListedAutomaticallyWithNameCommentTextAndEnabledState() throws Exception {
        List<SwrlEngineService.EmbeddedRule> rules = service().listEmbeddedRules("p1", null);

        assertEquals(List.of("HotDiscountRule", "InvalidDiscountRule", "LessSpicyDiscountRule", "MeatyPizzaRule",
                "MultipleSSNRule", "SpicinessMismatchRule", "TestHotDiscountRule", "VeryHighCalorieRule"),
                rules.stream().map(SwrlEngineService.EmbeddedRule::name).toList());
        assertTrue(rules.stream().allMatch(rule -> rule.ruleText().contains(" -> ")), rules.toString());
        assertTrue(rules.stream().filter(rule -> !rule.name().equals("TestHotDiscountRule"))
                .allMatch(rule -> rule.ruleText().contains(" ^ ")), rules.toString());
        SwrlEngineService.EmbeddedRule ssn = rules.stream().filter(rule -> rule.name().equals("MultipleSSNRule")).findFirst().orElseThrow();
        assertEquals("Test/data quality: an employee with two different ssn values.", ssn.comment());
        assertTrue(ssn.ruleText().contains("swrlb:notEqual(?s1, ?s2)"), ssn.ruleText());
        assertTrue(ssn.enabled());
        SwrlEngineService.EmbeddedRule query = rules.stream().filter(rule -> rule.name().equals("TestHotDiscountRule")).findFirst().orElseThrow();
        assertFalse(query.enabled());
        assertTrue(query.notice().contains("SQWRL"), query.notice());
        assertEquals(7, rules.stream().filter(SwrlEngineService.EmbeddedRule::enabled).count());
    }

    @Test
    void listingDoesNotDependOnTheRulesBeingAbleToRun() throws Exception {
        List<SwrlEngineService.EmbeddedRule> rules = serviceWithoutNormalizing().listEmbeddedRules("p1", null);

        assertEquals(8, rules.size(), rules.toString());
    }

    @Test
    void theRuleTextCanAlsoBeWrittenWithoutTheRuleEngine() throws Exception {
        OWLOntology ontology = parsedWithoutNormalizing(text("/pizza_editor_export.rdf"));
        SWRLRule ssn = ontology.getAxioms(AxiomType.SWRL_RULE).stream()
                .filter(rule -> rule.toString().contains("notEqual")).findFirst().orElseThrow();

        String text = OntologyRules.fallbackText(ssn);

        assertTrue(text.contains("Employee(?e)") && text.contains("ssn(?e, ?s1)") && text.contains("swrlb:notEqual(?s1, ?s2)")
                && text.endsWith("-> EmployeeWithMultipleSSN(?e)"), text);
    }

    @Test
    void swrlaIsRuleEnabledFalseListsTheRuleAsDisabledAndExecuteAllSkipsIt() throws Exception {
        String edited = text("/pizza_editor_export.rdf").replaceFirst(">true</swrla:isRuleEnabled>", ">false</swrla:isRuleEnabled>");
        SwrlEngineService service = serviceServing(() -> {
            OWLOntology ontology = parsedWithoutNormalizing(edited);
            PlainLiteralNormalizer.apply(ontology);
            return ontology;
        });

        List<SwrlEngineService.EmbeddedRule> rules = service.listEmbeddedRules("p1", null);
        ExecutionResult result = service.executeRules("p1");

        assertEquals(1, rules.stream().filter(rule -> !rule.enabled() && !rule.name().equals("TestHotDiscountRule")).count(), rules.toString());
        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertEquals(6, result.getExecutedRuleNames().size(), result.getExecutedRuleNames().toString());
    }

    @Test
    void aNewRuleValidatesEvenThoughAnotherEmbeddedRuleWouldFailAtRunTime() throws Exception {
        ValidationResult result = serviceWithoutNormalizing().validateRule("p1", NEW_RULE);

        assertTrue(result.isValid(), String.valueOf(result.getErrorMessage()));
    }

    @Test
    void aNewRuleValidatesThroughTheRealFetch() throws Exception {
        ValidationResult result = service().validateRule("p1", NEW_RULE);

        assertTrue(result.isValid(), String.valueOf(result.getErrorMessage()));
    }

    @Test
    void aNewRuleCanBeParsedAndTestedEvenThoughAnotherEmbeddedRuleWouldFailAtRunTime() throws Exception {
        SwrlEngineService service = serviceWithoutNormalizing();

        SwrlEngineService.RuleParse parsed = service.parseRule("p1", NEW_RULE, null);
        ExecutionResult tested = service.testSingleRule("p1", NEW_RULE);

        assertTrue(parsed.valid(), String.valueOf(parsed.errors()));
        assertTrue(tested.isSuccess(), String.valueOf(tested.getErrorMessage()));
        assertTrue(tested.getInferredAxiomsCount() > 0, tested.toString());
    }

    @Test
    void executeAllRunsEveryEnabledRuleAndReturnsTheirConclusions() throws Exception {
        ExecutionResult result = service().executeRules("p1");

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertEquals(7, result.getExecutedRuleNames().size(), result.getExecutedRuleNames().toString());
        assertTrue(result.getSkippedRules().isEmpty(), result.getSkippedRules().toString());
        assertTrue(result.getInferredAxiomsCount() > 0);
        assertFalse(result.getInferredAxioms().isEmpty());
    }

    @Test
    void oneRuleFailingAtRunTimeIsNamedAndTheOtherRulesStillReturnTheirConclusions() throws Exception {
        ExecutionResult result = serviceWithoutNormalizing().executeRules("p1");

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertEquals("completed_with_warnings", result.getCompletion());
        assertEquals(1, result.getSkippedRules().size(), result.getSkippedRules().toString());
        assertEquals("MultipleSSNRule", result.getSkippedRules().get(0).ruleName());
        assertTrue(result.getSkippedRules().get(0).reason().startsWith("Rule MultipleSSNRule could not run: "), result.getSkippedRules().get(0).reason());
        assertEquals(6, result.getExecutedRuleNames().size(), result.getExecutedRuleNames().toString());
        assertFalse(result.getExecutedRuleNames().contains("MultipleSSNRule"));
        assertTrue(result.getInferredAxiomsCount() > 0);
    }

    @Test
    void theSameHoldsForTheSelectedRulesPath() throws Exception {
        SwrlEngineService service = serviceWithoutNormalizing();
        List<String> ids = service.listEmbeddedRules("p1", null).stream()
                .filter(rule -> rule.name().equals("MultipleSSNRule") || rule.name().equals("HotDiscountRule"))
                .map(SwrlEngineService.EmbeddedRule::id).toList();

        ExecutionResult result = service.executeSelectedRules("p1", ids);

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertEquals(List.of("HotDiscountRule"), result.getExecutedRuleNames());
        assertEquals("MultipleSSNRule", result.getSkippedRules().get(0).ruleName());
    }

    @Test
    void aDraftProjectUsesTheSameFetchAndTheSameFixes() throws Exception {
        SwrlEngineService service = service();

        List<SwrlEngineService.EmbeddedRule> rules = service.listEmbeddedRules("p1", "u1");
        ExecutionResult result = service.executeRules("p1", "u1");

        assertTrue(requested.get().contains("draft=true") && requested.get().contains("userId=u1"), requested.get());
        assertEquals(8, rules.size());
        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertEquals(7, result.getExecutedRuleNames().size());
        assertTrue(service.validateRule("p1", NEW_RULE, "u1").isValid());
    }
}
