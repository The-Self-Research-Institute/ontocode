package self.research.ontology.owlEditor.service.spreadsheet;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.spreadsheet.GenerationResult.Issue;
import self.research.ontology.owlEditor.service.spreadsheet.GenerationResult.Severity;
import self.research.ontology.owlEditor.service.spreadsheet.rule.EntityKind;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AxiomGeneratorTest {

    private static final String NS = "http://example.org/sensor#";
    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    private static final String SENSOR_RULE = """
        Individual: @A*
          Types: SensorReading
          Facts: hasMeasurementValue @B*(xsd:decimal),
                 hasUnit @C*(xsd:string),
                 hasReadingDate @D*(xsd:dateTime),
                 hasLocation @E*,
                 hasAnalyte @F*(xsd:string)
          Annotations: rdfs:label @G*
        """;

    private static OntologyLookup sensorOntology() {
        Map<String, OntologyLookup.KnownEntity> byIri = new HashMap<>();
        java.util.function.BiConsumer<String, EntityKind> add = (local, kind) ->
            byIri.put(NS + local, new OntologyLookup.KnownEntity(NS + local, Set.of(kind), null));
        add.accept("SensorReading", EntityKind.CLASS);
        add.accept("Location", EntityKind.CLASS);
        add.accept("hasLocation", EntityKind.OBJECT_PROPERTY);
        add.accept("LabA", EntityKind.INDIVIDUAL);
        add.accept("LabB", EntityKind.INDIVIDUAL);
        add.accept("LabC", EntityKind.INDIVIDUAL);
        Map<String, String> ranges = Map.of("hasMeasurementValue", "decimal", "hasUnit", "string",
            "hasReadingDate", "dateTime", "hasAnalyte", "string");
        ranges.forEach((p, r) -> byIri.put(NS + p,
            new OntologyLookup.KnownEntity(NS + p, Set.of(EntityKind.DATA_PROPERTY), XSD + r)));
        Map<String, OntologyLookup.KnownEntity> byLabel = Map.of("Sensor reading",
            new OntologyLookup.KnownEntity(NS + "SensorReading", Set.of(EntityKind.CLASS), null));
        return new OntologyLookup() {
            public String defaultNamespace() {
                return NS;
            }

            public Map<String, String> prefixes() {
                return Map.of("sen", NS);
            }

            public Optional<KnownEntity> byIri(String iri) {
                return Optional.ofNullable(byIri.get(iri));
            }

            public List<KnownEntity> byLabel(String label) {
                return byLabel.containsKey(label) ? List.of(byLabel.get(label)) : List.of();
            }
        };
    }

    private static SpreadsheetWorkbook sensorSheet(int rows) {
        String[] locs = {"LabA", "LabB", "LabC"};
        String[] analytes = {"Lead", "Cadmium", "Nitrate", "Arsenic"};
        List<List<String>> data = new ArrayList<>();
        data.add(List.of("SensorID", "MeasurementValue", "Unit", "ReadingDate", "Location", "Analyte", "Label"));
        for (int i = 1; i <= rows; i++) {
            data.add(List.of("S%03d".formatted(i), String.valueOf(20 + i / 10.0), "mg/L",
                "2026-09-%02dT10:%02d:00".formatted(1 + i % 28, i % 60), locs[i % 3], analytes[i % 4],
                "Sensor reading " + i));
        }
        return workbook("Readings", data);
    }

    private static SpreadsheetWorkbook workbook(String sheet, List<List<String>> rows) {
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        return new SpreadsheetWorkbook("test.xlsx", "xlsx",
            List.of(new SpreadsheetWorkbook.Sheet(sheet, rows.size(), cols, rows)));
    }

    private static RuleSpec rule(String text) {
        return new RuleSpec("Readings", "A", "A", "2", "+", text, null, true);
    }

    private static Map<String, Long> countByType(GenerationResult r) {
        return r.axioms().stream().collect(Collectors.groupingBy(GenerationResult.Axiom::type, Collectors.counting()));
    }

    @Test
    void sensorSheetMatchesCellfieAxiomCount() {
        GenerationResult r = AxiomGenerator.generate(sensorSheet(20), List.of(rule(SENSOR_RULE)), sensorOntology());

        assertFalse(r.hasErrors(), () -> r.issues().toString());
        assertTrue(r.issues().isEmpty(), () -> r.issues().toString());
        assertEquals(160, r.axioms().size(), "Cellfie generated 160 axioms for the same 20 rows");
        assertEquals(20, countByType(r).get("Declaration"));
        assertEquals(20, countByType(r).get("ClassAssertion"));
        assertEquals(80, countByType(r).get("DataPropertyAssertion"));
        assertEquals(20, countByType(r).get("ObjectPropertyAssertion"));
        assertEquals(20, countByType(r).get("AnnotationAssertion"));
        assertEquals(20, r.rowsProcessed());

        MutationOp create = r.ops().stream().filter(o -> o.type().equals("createIndividual")).findFirst().orElseThrow();
        assertEquals(NS + "S001", create.iri());
        assertEquals(NS + "SensorReading", create.classIri(), "the first type goes into createIndividual");

        MutationOp value = r.ops().stream()
            .filter(o -> o.type().equals("addDataPropertyAssertion") && o.iri().equals(NS + "S001")
                && o.property().equals(NS + "hasMeasurementValue"))
            .findFirst().orElseThrow();
        assertEquals("20.1", value.value());
        assertEquals(XSD + "decimal", value.datatype(), "full datatype IRI, as the SPARQL builder expects");

        MutationOp link = r.ops().stream()
            .filter(o -> o.type().equals("addObjectPropertyAssertion") && o.iri().equals(NS + "S001"))
            .findFirst().orElseThrow();
        assertEquals(NS + "LabB", link.target(), "links to the existing LabB, no new individual");
        assertTrue(r.ops().stream().noneMatch(o -> o.type().equals("createIndividual") && o.iri().equals(NS + "LabB")));

        assertTrue(r.axioms().stream().anyMatch(a ->
            a.text().equals("S001 hasMeasurementValue \"20.1\"^^xsd:decimal") && a.row() == 2));
    }

    @Test
    void fiveHundredRowsGiveFiveHundredIndividuals() {
        GenerationResult r = AxiomGenerator.generate(sensorSheet(500), List.of(rule(SENSOR_RULE)), sensorOntology());
        assertFalse(r.hasErrors());
        assertEquals(500, r.ops().stream().filter(o -> o.type().equals("createIndividual")).count());
        assertEquals(4000, r.axioms().size());
    }

    private static OntologyLookup peoLikeOntology() {
        String peo = "https://w3id.org/peo/";
        String peop = "https://w3id.org/peo/property/";
        String other = "https://example.org/other#";
        Map<String, OntologyLookup.KnownEntity> byIri = new HashMap<>();
        byIri.put(peo + "AirMeasurementDatum", new OntologyLookup.KnownEntity(peo + "AirMeasurementDatum", Set.of(EntityKind.CLASS), null));
        byIri.put(peop + "hasDecimalValue", new OntologyLookup.KnownEntity(peop + "hasDecimalValue", Set.of(EntityKind.DATA_PROPERTY), XSD + "decimal"));
        byIri.put(peop + "hasCode", new OntologyLookup.KnownEntity(peop + "hasCode", Set.of(EntityKind.DATA_PROPERTY), XSD + "string"));
        byIri.put(other + "hasCode", new OntologyLookup.KnownEntity(other + "hasCode", Set.of(EntityKind.DATA_PROPERTY), XSD + "string"));
        return new OntologyLookup() {
            public String defaultNamespace() {
                return peo + "ontology#";
            }

            public Map<String, String> prefixes() {
                return Map.of("peo", peo, "peop", peop);
            }

            public Optional<KnownEntity> byIri(String iri) {
                return Optional.ofNullable(byIri.get(iri));
            }

            public List<KnownEntity> byLabel(String label) {
                return List.of();
            }

            public List<KnownEntity> byShortName(String name) {
                return byIri.values().stream().filter(e -> e.iri().endsWith("#" + name) || e.iri().endsWith("/" + name)).toList();
            }
        };
    }

    @Test
    void plainNamesFindEntitiesInOtherNamespacesLikeProtege() {
        SpreadsheetWorkbook sheet = workbook("Readings", List.of(List.of("ID", "Value"), List.of("AQ0001", "47.8")));

        GenerationResult r = AxiomGenerator.generate(sheet,
            List.of(rule("Individual: @A* Types: AirMeasurementDatum Facts: hasDecimalValue @B*(xsd:decimal)")),
            peoLikeOntology());

        assertFalse(r.hasErrors(), r.issues().toString());
        MutationOp create = r.ops().stream().filter(o -> o.type().equals("createIndividual")).findFirst().orElseThrow();
        assertEquals("https://w3id.org/peo/AirMeasurementDatum", create.classIri(), "PEO's class, not a new duplicate");
        assertEquals(0, r.ops().stream().filter(o -> o.type().equals("createClass")).count());
        assertTrue(r.ops().stream().anyMatch(o -> "https://w3id.org/peo/property/hasDecimalValue".equals(o.property())));
    }

    @Test
    void aPlainNameInSeveralNamespacesAsksForAPrefix() {
        SpreadsheetWorkbook sheet = workbook("Readings", List.of(List.of("ID", "Code"), List.of("AQ0001", "x")));

        GenerationResult r = AxiomGenerator.generate(sheet,
            List.of(rule("Individual: @A* Facts: hasCode @B*")), peoLikeOntology());

        List<Issue> errors = r.issues().stream().filter(i -> i.severity() == Severity.ERROR).toList();
        assertEquals(1, errors.size(), r.issues().toString());
        assertTrue(errors.get(0).message().contains("matches 2 entities"));
        assertTrue(r.ops().stream().noneMatch(o -> "addDataPropertyAssertion".equals(o.type())), "nothing guessed");
    }

    @Test
    void aNameThatExistsAsAnotherKindIsDeclaredAsThisKindTooLikeCellfie() {
        SpreadsheetWorkbook sheet = workbook("Readings", List.of(List.of("Name"), List.of("LabA"), List.of("Location")));

        GenerationResult r = AxiomGenerator.generate(sheet, List.of(rule("Class: @A*")), sensorOntology());

        assertFalse(r.hasErrors());
        List<String> classesDeclared = r.ops().stream().filter(o -> o.type().equals("createClass")).map(MutationOp::iri).toList();
        assertEquals(List.of(NS + "LabA"), classesDeclared, "LabA (an individual) becomes a class too; Location already is one");
        assertTrue(r.issues().isEmpty(), "punning is silent, as in Cellfie");
    }

    @Test
    void aReferencedIndividualUsedAsAClassIsDeclaredAsAClass() {
        GenerationResult r = AxiomGenerator.generate(sensorSheet(2),
            List.of(rule("Individual: @A* Types: LabB")), sensorOntology());

        assertEquals(1, r.ops().stream().filter(o -> o.type().equals("createClass") && o.iri().equals(NS + "LabB")).count());
        assertFalse(r.issues().stream().anyMatch(i -> i.message().contains("will be created")),
            "LabB exists, so no 'will be created' warning");
    }

    @Test
    void aHugeEndRowOrColumnStopsAtTheEndOfTheSheet() {
        SpreadsheetWorkbook sheet = sensorSheet(5);
        RuleSpec.Range range = new RuleSpec("Readings", "A", "XFD", "2", "2000000000", "Individual: @A*", null, true)
            .resolve(sheet.sheets().get(0));
        assertEquals(sheet.sheets().get(0).rowCount(), range.endRow());
        assertEquals(sheet.sheets().get(0).columnCount(), range.endColumn());

        long start = System.nanoTime();
        GenerationResult r = AxiomGenerator.generate(sheet,
            List.of(new RuleSpec("Readings", "A", "A", "2", "2000000000", "Individual: @A* Types: SensorReading", null, true)),
            sensorOntology());
        assertFalse(r.hasErrors());
        assertEquals(5, r.ops().stream().filter(o -> o.type().equals("createIndividual")).count());
        assertTrue(r.issues().size() < 5, "no warning per non-existent row");
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000, "finishes quickly");
    }

    @Test
    void writesDecimalsInTheFormTheStoreKeeps() {
        String decimal = AxiomGenerator.XSD + "decimal";
        assertEquals("27.0", AxiomGenerator.canonical("27", decimal));
        assertEquals("23.7", AxiomGenerator.canonical("23.70", decimal));
        assertEquals("0.0", AxiomGenerator.canonical("0", decimal));
        assertEquals("-0.5", AxiomGenerator.canonical("-.5", decimal));
        assertEquals("27", AxiomGenerator.canonical("27", AxiomGenerator.XSD + "integer"));
    }

    @Test
    void reportsDatatypeMismatchesPerRowBeforeCommit() {
        List<List<String>> rows = new ArrayList<>(sensorSheet(6).sheets().get(0).rows());
        rows.set(3, List.of("S003", "abc", "mg/L", "2026-09-04T10:03:00", "LabA", "Lead", "x"));
        rows.set(5, List.of("S005", "28.5", "mg/L", "not-a-date", "LabC", "Lead", "y"));
        rows.set(6, List.of("", "1", "", "", "", "", ""));
        GenerationResult r = AxiomGenerator.generate(workbook("Readings", rows), List.of(rule(SENSOR_RULE)), sensorOntology());

        assertTrue(r.hasErrors());
        List<Issue> errors = r.issues().stream().filter(i -> i.severity() == Severity.ERROR).toList();
        assertEquals(2, errors.size(), errors::toString);
        assertEquals(4, errors.get(0).row());
        assertEquals("B4", errors.get(0).cell());
        assertTrue(errors.get(0).message().contains("'abc' is not a valid xsd:decimal"), errors.get(0).message());
        assertEquals("D6", errors.get(1).cell());

        Issue skipped = r.issues().stream().filter(i -> i.severity() == Severity.WARNING).findFirst().orElseThrow();
        assertEquals("A7", skipped.cell());
        assertTrue(r.ops().stream().noneMatch(o -> "abc".equals(o.value())), "no malformed literal is produced");
    }

    @Test
    void emptyValueCellsOmitOnlyThatAxiom() {
        List<List<String>> rows = new ArrayList<>(sensorSheet(2).sheets().get(0).rows());
        rows.set(1, List.of("S001", "23.7", "mg/L", "2026-09-02T10:01:00", "", "Lead", "x"));
        GenerationResult r = AxiomGenerator.generate(workbook("Readings", rows), List.of(rule(SENSOR_RULE)), sensorOntology());
        assertTrue(r.issues().isEmpty(), () -> r.issues().toString());
        assertEquals(15, r.axioms().size(), "row 2 lost only its hasLocation axiom");
    }

    @Test
    void typesValuesFromThePropertyRangeWhenTheRuleDoesNot() {
        GenerationResult r = AxiomGenerator.generate(sensorSheet(1),
            List.of(rule("Individual: @A* Facts: hasMeasurementValue @B*, hasReadingDate @D*")), sensorOntology());
        assertFalse(r.hasErrors());
        assertEquals(Set.of(XSD + "decimal", XSD + "dateTime"), r.ops().stream()
            .filter(o -> o.type().equals("addDataPropertyAssertion")).map(MutationOp::datatype).collect(Collectors.toSet()));
    }

    @Test
    void unknownPropertyIsOneErrorAndMissingClassIsCreated() {
        GenerationResult r = AxiomGenerator.generate(sensorSheet(3),
            List.of(rule("Individual: @A* Types: Thermometer Facts: hasColour @C*")), sensorOntology());

        List<Issue> errors = r.issues().stream().filter(i -> i.severity() == Severity.ERROR).toList();
        assertEquals(1, errors.size(), "reported once, not once per row");
        assertTrue(errors.get(0).message().contains("Property 'hasColour' is not in the ontology"));
        assertEquals(null, errors.get(0).row(), "a rule-wide problem is not tied to one row");
        assertEquals(1, r.ops().stream().filter(o -> o.type().equals("createClass")).count());
        assertTrue(r.issues().stream().anyMatch(i -> i.message().contains("Thermometer is not in the ontology and will be created")));
    }

    @Test
    void allEntityKindsAndForwardReferencesAcrossRules() {
        List<List<String>> rows = List.of(
            List.of("Name", "Parent", "Range", "Label"),
            List.of("TemperatureReading", "SensorReading", "xsd:decimal", "Temperature reading"),
            List.of("PressureReading", "SensorReading", "xsd:decimal", "Pressure reading"));
        SpreadsheetWorkbook wb = workbook("Readings", rows);
        GenerationResult r = AxiomGenerator.generate(wb, List.of(
            new RuleSpec("Readings", "A", "A", "2", "2", "Individual: T1 Types: @A2 Facts: hasCelsius \"21.5\"", null, true),
            new RuleSpec("Readings", "A", "A", "2", "+", "Class: @A* SubClassOf: @B* Annotations: rdfs:label @D*([en])", null, true),
            new RuleSpec("Readings", "A", "A", "2", "2",
                "DataProperty: hasCelsius Domain: @A* Range: @C* Characteristics: Functional", null, true),
            new RuleSpec("Readings", "A", "A", "2", "2",
                "ObjectProperty: measuredAt Domain: SensorReading Range: Location InverseOf: hasLocation", null, true),
            new RuleSpec("Readings", "A", "A", "2", "2", "AnnotationProperty: sourceNote SubPropertyOf: rdfs:comment", null, true),
            new RuleSpec("Readings", "A", "A", "2", "2", "Datatype: celsius", null, true),
            new RuleSpec("Readings", "A", "A", "2", "2", "Class: Disabled", null, false)),
            sensorOntology());

        assertFalse(r.hasErrors(), () -> r.issues().toString());
        Set<String> types = r.ops().stream().map(MutationOp::type).collect(Collectors.toSet());
        assertTrue(types.containsAll(Set.of("createIndividual", "createClass", "createDataProperty", "createObjectProperty",
            "createAnnotationProperty", "createDatatype", "addPropertyDomain", "addPropertyRange", "addCharacteristic",
            "addInverseProperty", "addAnnotation", "addDataPropertyAssertion")), types::toString);

        MutationOp cls = r.ops().stream().filter(o -> o.type().equals("createClass")
            && o.iri().equals(NS + "TemperatureReading")).findFirst().orElseThrow();
        assertEquals(NS + "SensorReading", cls.parent(), "first SubClassOf becomes the parent, not owl:Thing");
        MutationOp celsius = r.ops().stream().filter(o -> o.type().equals("addDataPropertyAssertion")).findFirst().orElseThrow();
        assertEquals(NS + "hasCelsius", celsius.property());
        assertTrue(r.ops().stream().noneMatch(o -> NS.concat("Disabled").equals(o.iri())), "inactive rules do not run");
        assertTrue(r.ops().stream().anyMatch(o -> o.type().equals("addAnnotation") && "en".equals(o.language())));
    }

    @Test
    void ruleProblemsAreReportedWithoutRunning() {
        GenerationResult r = AxiomGenerator.generate(sensorSheet(2), List.of(
            rule("Individual: @A*\n  Types:"),
            new RuleSpec("Nope", "A", "A", "2", "+", SENSOR_RULE, null, true),
            new RuleSpec("Readings", "A", "A", "x", "+", SENSOR_RULE, null, true),
            rule("Individual: @A*(mm:frobnicate)"),
            rule("Individual: @A* Types: zz:Thing")),
            sensorOntology());

        List<Issue> errors = r.issues().stream().filter(i -> i.severity() == Severity.ERROR).toList();
        assertEquals(List.of(1, 2, 3, 4, 5), errors.stream().map(Issue::rule).distinct().toList(), errors::toString);
        assertEquals(2, errors.get(0).line());
        assertTrue(errors.get(1).message().contains("Sheet 'Nope'"));
        assertTrue(errors.get(3).message().contains("not supported yet"));
        assertTrue(errors.get(4).message().contains("Prefix 'zz:'"));
    }

    @Test
    void rejectsIrisThatWouldBreakSparql() {
        List<List<String>> rows = List.of(
            List.of("Id", "Location"),
            List.of("S001", "http://x.org/a> . <http://x.org/b"),
            List.of("http://x.org/S 002", "LabA"));
        GenerationResult r = AxiomGenerator.generate(workbook("Readings", rows),
            List.of(rule("Individual: @A* Facts: hasLocation @B*"),
                rule("Individual: @A*([en\"]) Annotations: rdfs:label @B*")),
            sensorOntology());

        List<Issue> errors = r.issues().stream().filter(i -> i.severity() == Severity.ERROR).toList();
        assertTrue(errors.stream().anyMatch(i -> "B2".equals(i.cell()) && i.message().contains("not a valid IRI")), errors::toString);
        assertTrue(errors.stream().anyMatch(i -> i.rule() == 2), "bad language tag rejects the rule");
        assertTrue(r.ops().stream().allMatch(o -> o.target() == null || AxiomGenerator.isSafeIri(o.target())));
        assertTrue(r.ops().stream().allMatch(o -> AxiomGenerator.isSafeIri(o.iri())));
    }

    @Test
    void encodingsLabelsAndPrefixesAreDeterministic() {
        List<List<String>> rows = List.of(List.of("Name"), List.of("Lab Room 1"));
        SpreadsheetWorkbook wb = workbook("Readings", rows);
        List<RuleSpec> rules = List.of(
            new RuleSpec("Readings", "A", "A", "2", "+", "Individual: @A*(mm:camelCaseEncode) Types: 'Sensor reading'", null, true),
            new RuleSpec("Readings", "A", "A", "2", "+", "Individual: @A*(rdfs:label mm:uuidEncode mm:prefix=\"sen\")", null, true),
            new RuleSpec("Readings", "A", "A", "2", "+", "Individual: @A*(mm:prepend(\"ID \") mm:snakeCaseEncode)", null, true));

        GenerationResult first = AxiomGenerator.generate(wb, rules, sensorOntology());
        GenerationResult second = AxiomGenerator.generate(wb, rules, sensorOntology());

        assertFalse(first.hasErrors(), () -> first.issues().toString());
        assertEquals(first.ops(), second.ops(), "preview and commit must produce identical ops");
        List<String> created = first.ops().stream().filter(o -> o.type().equals("createIndividual")).map(MutationOp::iri).toList();
        assertEquals(NS + "LabRoom1", created.get(0));
        assertTrue(created.get(1).matches(NS.replace(".", "\\.") + "[0-9a-f-]{36}"), created.get(1));
        assertEquals(NS + "ID_Lab_Room_1", created.get(2));
        assertEquals(NS + "SensorReading", first.ops().get(0).classIri(), "'Sensor reading' resolved by label");
        assertTrue(first.ops().stream().anyMatch(o -> o.type().equals("createIndividual") && "Lab Room 1".equals(o.label())));
    }
}
