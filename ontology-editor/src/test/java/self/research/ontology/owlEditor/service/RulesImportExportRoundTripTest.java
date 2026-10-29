package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLDArgument;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.model.SWRLVariable;
import self.research.ontology.common.rules.DisabledRules;
import self.research.ontology.owlEditor.util.OWLFormatConverter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulesImportExportRoundTripTest {

    private static final String NS = "http://example.org/clinic#";
    private static final String SWRLB = "http://www.w3.org/2003/11/swrlb#";
    private static final String SQWRL = "http://sqwrl.stanford.edu/ontologies/built-ins/3.4/sqwrl.owl#";
    private static final IRI SWRLA = IRI.create("http://swrl.stanford.edu/ontologies/3.3/swrla.owl#isRuleEnabled");
    private static final String SWRL_IMP = "http://www.w3.org/2003/11/swrl#Imp";

    static final OWLDataFactory F = OWLManager.getOWLDataFactory();
    static String sourceRdfXml;
    static Map<String, Summary> expected;

    record Summary(String body, String label, String comment, boolean disabled, boolean protegeFlag) {
    }

    @BeforeAll
    static void build() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/clinic"));
        var patient = F.getOWLClass(IRI.create(NS + "Patient"));
        var child = F.getOWLClass(IRI.create(NS + "Child"));
        var senior = F.getOWLClass(IRI.create(NS + "Senior"));
        var hasAge = F.getOWLDataProperty(IRI.create(NS + "hasAge"));
        var hasParent = F.getOWLObjectProperty(IRI.create(NS + "hasParent"));
        var hasGuardian = F.getOWLObjectProperty(IRI.create(NS + "hasGuardian"));
        for (var entity : List.of(patient, child, senior, hasAge, hasParent, hasGuardian)) {
            manager.addAxiom(ontology, F.getOWLDeclarationAxiom(entity));
        }
        SWRLVariable p = F.getSWRLVariable(IRI.create("urn:swrl#p"));
        SWRLVariable a = F.getSWRLVariable(IRI.create("urn:swrl#a"));
        SWRLVariable q = F.getSWRLVariable(IRI.create("urn:swrl#q"));

        SWRLAtom isPatient = F.getSWRLClassAtom(patient, p);
        SWRLAtom age = F.getSWRLDataPropertyAtom(hasAge, p, a);
        SWRLAtom under18 = F.getSWRLBuiltInAtom(IRI.create(SWRLB + "lessThan"), List.<SWRLDArgument>of(a, F.getSWRLLiteralArgument(F.getOWLLiteral(18))));
        SWRLAtom over65 = F.getSWRLBuiltInAtom(IRI.create(SWRLB + "greaterThanOrEqual"), List.<SWRLDArgument>of(a, F.getSWRLLiteralArgument(F.getOWLLiteral(65))));
        SWRLAtom selectP = F.getSWRLBuiltInAtom(IRI.create(SQWRL + "select"), List.<SWRLDArgument>of(F.getSWRLLiteralArgument(F.getOWLLiteral("x"))));

        List<SWRLRule> rules = new ArrayList<>();
        rules.add(F.getSWRLRule(List.of(isPatient, age, under18), List.of(F.getSWRLClassAtom(child, p)),
                annotations("Age under 18", "Patients younger than eighteen are children", null)));
        rules.add(F.getSWRLRule(List.of(isPatient, age, over65), List.of(F.getSWRLClassAtom(senior, p)),
                annotations("Senior age", null, DisabledRules.ENABLED, false)));
        rules.add(F.getSWRLRule(List.of(isPatient, F.getSWRLObjectPropertyAtom(hasParent, p, q)),
                List.of(F.getSWRLObjectPropertyAtom(hasGuardian, p, q)),
                annotations("Parent is guardian", "Copies the parent link", null)));
        rules.add(F.getSWRLRule(List.of(isPatient, age, over65, selectP), List.of(),
                annotations("Seniors query", null, null)));
        rules.add(F.getSWRLRule(List.of(isPatient, age, under18, F.getSWRLBuiltInAtom(IRI.create(SWRLB + "add"),
                        List.<SWRLDArgument>of(F.getSWRLVariable(IRI.create("urn:swrl#n")), a, F.getSWRLLiteralArgument(F.getOWLLiteral(1))))),
                List.of(F.getSWRLClassAtom(child, p)), annotations("Next year child", "Uses add built-in", null)));
        rules.add(F.getSWRLRule(List.of(isPatient, age, over65), List.of(F.getSWRLClassAtom(senior, p)),
                annotations("Protege disabled", "Written by another tool", SWRLA, false)));
        for (SWRLRule rule : rules) {
            manager.addAxiom(ontology, rule);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(ontology, new RDFXMLDocumentFormat(), out);
        sourceRdfXml = out.toString(StandardCharsets.UTF_8);
        expected = summarise(load(sourceRdfXml));
        assertEquals(6, expected.size());
    }

    private static Set<OWLAnnotation> annotations(String label, String comment, IRI flag, boolean value) {
        Set<OWLAnnotation> set = new java.util.LinkedHashSet<>(annotations(label, comment, null));
        set.add(F.getOWLAnnotation(F.getOWLAnnotationProperty(flag), F.getOWLLiteral(value)));
        return set;
    }

    private static Set<OWLAnnotation> annotations(String label, String comment, Object ignored) {
        Set<OWLAnnotation> set = new java.util.LinkedHashSet<>();
        set.add(F.getOWLAnnotation(F.getRDFSLabel(), F.getOWLLiteral(label)));
        if (comment != null) {
            set.add(F.getOWLAnnotation(F.getRDFSComment(), F.getOWLLiteral(comment)));
        }
        return set;
    }

    static OWLOntology load(String text) throws Exception {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(text));
    }

    static Map<String, Summary> summarise(OWLOntology ontology) {
        Map<String, Summary> byName = new TreeMap<>();
        for (SWRLRule rule : ontology.getAxioms(AxiomType.SWRL_RULE)) {
            String label = null;
            String comment = null;
            boolean protege = false;
            for (OWLAnnotation annotation : rule.getAnnotations()) {
                String value = annotation.getValue().asLiteral().map(OWLLiteral::getLiteral).orElse("");
                if (annotation.getProperty().isLabel()) {
                    label = value;
                } else if (annotation.getProperty().isComment()) {
                    comment = value;
                } else if (annotation.getProperty().getIRI().equals(SWRLA)) {
                    protege = true;
                }
            }
            byName.put(label, new Summary(rule.getAxiomWithoutAnnotations().toString().replace("urn:swrl:var#", "urn:swrl#"), label, comment,
                    DisabledRules.isDisabled(rule), protege));
        }
        return byName;
    }

    private static void assertSame(Map<String, Summary> actual, String path) {
        assertEquals(expected, actual, path);
    }

    @Test
    void theFixtureCarriesTheDisabledStateAndAProtegeFlag() {
        assertTrue(expected.get("Senior age").disabled());
        assertTrue(expected.get("Protege disabled").disabled());
        assertTrue(expected.get("Protege disabled").protegeFlag());
        assertEquals(1, expected.values().stream().filter(s -> s.body().contains("sqwrl")).count());
    }

    @Test
    void anRdfXmlUploadKeepsEveryRuleAsTriples() throws Exception {
        Model model = Rio.parse(new ByteArrayInputStream(sourceRdfXml.getBytes(StandardCharsets.UTF_8)), "http://example.org/", RDFFormat.RDFXML);

        assertEquals(6, model.filter(null, RDF.TYPE, org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance().createIRI(SWRL_IMP)).size());
        assertEquals(0, model.filter(null, org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance().createIRI(SWRLA.toString()), null).stream()
                .filter(s -> !"false".equals(s.getObject().stringValue())).count());
        assertSame(summarise(load(sourceRdfXml)), "rdfxml upload");
    }

    @Test
    void everyRdfSerialisationOfTheStoredTriplesReloadsWithTheSameRules() throws Exception {
        Model model = Rio.parse(new ByteArrayInputStream(sourceRdfXml.getBytes(StandardCharsets.UTF_8)), "http://example.org/", RDFFormat.RDFXML);
        for (RDFFormat format : List.of(RDFFormat.RDFXML, RDFFormat.TURTLE, RDFFormat.NTRIPLES, RDFFormat.JSONLD)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Rio.write(model, out, format);
            String text = out.toString(StandardCharsets.UTF_8);
            Model again = Rio.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "http://example.org/", format);
            ByteArrayOutputStream back = new ByteArrayOutputStream();
            Rio.write(again, back, RDFFormat.RDFXML);
            assertSame(summarise(load(back.toString(StandardCharsets.UTF_8))), format.getName());
        }
    }

    @Test
    void owlApiSyntaxesConvertToTheStoredFormWithoutLosingRules(@TempDir Path dir) throws Exception {
        Map<String, OWLDocumentFormat> formats = new LinkedHashMap<>();
        formats.put("owlxml", new OWLXMLDocumentFormat());
        formats.put("functional", new FunctionalSyntaxDocumentFormat());
        formats.put("turtle", new TurtleDocumentFormat());
        for (var entry : formats.entrySet()) {
            OWLOntology source = load(sourceRdfXml);
            Path file = dir.resolve("upload-" + entry.getKey() + ".owl");
            try (var out = Files.newOutputStream(file)) {
                source.getOWLOntologyManager().saveOntology(source, entry.getValue(), out);
            }
            Path converted = OWLFormatConverter.convertToRDFXML(file);
            assertSame(summarise(load(Files.readString(converted))), "import " + entry.getKey());
        }
    }

    @Test
    void exportingToEveryOwlApiSyntaxKeepsEveryRule() throws Exception {
        OntologyExporter exporter = new OntologyExporter(null, null, null);
        for (String format : List.of("owlxml", "functional", "turtle")) {
            String converted = exporter.convertRdfXmlToOwlApiFormat(sourceRdfXml, format);
            assertSame(summarise(load(converted)), "export " + format);
        }
    }
}
