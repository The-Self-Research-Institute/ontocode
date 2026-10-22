package self.research.ontology.owlEditor.service.spreadsheet.rule;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.service.spreadsheet.SpreadsheetWorkbook;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.CellReference;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.Clause;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.Literal;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.Name;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.NameForm;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleAst.TransformationRule;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleParserTest {

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

    @Test
    void parsesTheCellfieSensorRule() {
        TransformationRule rule = RuleParser.parse(SENSOR_RULE);

        assertEquals(EntityKind.INDIVIDUAL, rule.kind());
        CellReference subject = assertInstanceOf(CellReference.class, rule.subject());
        assertEquals(1, subject.sheetColumn());
        assertTrue(subject.currentRow());
        assertNull(subject.sheet());

        assertEquals(3, rule.clauses().size());
        Clause types = rule.clauses().get(0);
        assertEquals(ClauseKind.TYPES, types.kind());
        assertEquals("SensorReading", ((Name) types.items().get(0).value()).text());

        Clause facts = rule.clauses().get(1);
        assertEquals(5, facts.items().size());
        assertEquals("hasMeasurementValue", ((Name) facts.items().get(0).property()).text());
        CellReference measurement = (CellReference) facts.items().get(0).value();
        assertEquals(2, measurement.sheetColumn());
        assertEquals("xsd:decimal", measurement.directives().get(0).name());
        assertTrue(((CellReference) facts.items().get(3).value()).directives().isEmpty());

        Clause annotations = rule.clauses().get(2);
        assertEquals(NameForm.PREFIXED, ((Name) annotations.items().get(0).property()).form());
    }

    @Test
    void parsesEveryEntityKind() {
        assertEquals(EntityKind.CLASS,
            RuleParser.parse("Class: @A* SubClassOf: Sensor, @B* EquivalentTo: @C* DisjointWith: Probe").kind());
        assertEquals(EntityKind.OBJECT_PROPERTY,
            RuleParser.parse("ObjectProperty: @A* Domain: Sensor Range: Location InverseOf: @B* "
                + "SubPropertyOf: relatedTo Characteristics: Functional, Transitive").kind());
        assertEquals(EntityKind.DATA_PROPERTY,
            RuleParser.parse("DataProperty: @A* Domain: ReferenceItem Range: @B* Characteristics: Functional "
                + "Annotations: rdfs:label @C*").kind());
        assertEquals(EntityKind.ANNOTATION_PROPERTY,
            RuleParser.parse("AnnotationProperty: @A* SubPropertyOf: rdfs:comment").kind());
        assertEquals(EntityKind.DATATYPE, RuleParser.parse("Datatype: @A* Annotations: rdfs:label @B*").kind());
        assertEquals(EntityKind.INDIVIDUAL,
            RuleParser.parse("Individual: @A* SameAs: @B* DifferentFrom: other").kind());
    }

    @Test
    void parsesReferencesNamesLiteralsAndDirectives() {
        TransformationRule rule = RuleParser.parse("""
            Individual:@Sensors!A5(rdfs:label mm:camelCaseEncode mm:prefix="peo" mm:prepend("ID-"))
              Types:'Sensor reading', <http://example.org/sensor#Probe>
              Facts: @B1 @*3, hasNote "Note \\"x\\""@en, hasCount "3"^^xsd:int
              Annotations: rdfs:comment @'Raw data'!C*([en])
            """);

        CellReference subject = (CellReference) rule.subject();
        assertEquals("Sensors", subject.sheet());
        assertEquals(5, subject.row());
        assertEquals(List.of("rdfs:label", "mm:camelCaseEncode", "mm:prefix", "mm:prepend"),
            subject.directives().stream().map(RuleAst.Directive::name).toList());
        assertEquals("peo", subject.directives().get(2).argument());
        assertEquals("ID-", subject.directives().get(3).argument());

        Clause types = rule.clauses().get(0);
        assertEquals(NameForm.QUOTED, ((Name) types.items().get(0).value()).form());
        assertEquals("http://example.org/sensor#Probe", ((Name) types.items().get(1).value()).text());

        Clause facts = rule.clauses().get(1);
        CellReference headerProperty = (CellReference) facts.items().get(0).property();
        assertEquals(1, headerProperty.row());
        assertTrue(((CellReference) facts.items().get(0).value()).currentColumn());
        Literal note = (Literal) facts.items().get(1).value();
        assertEquals("Note \"x\"", note.text());
        assertEquals("en", note.language());
        assertEquals("xsd:int", ((Literal) facts.items().get(2).value()).datatype());

        CellReference comment = (CellReference) rule.clauses().get(2).items().get(0).value();
        assertEquals("Raw data", comment.sheet());
        assertEquals("lang", comment.directives().get(0).name());
        assertEquals("en", comment.directives().get(0).argument());
    }

    @Test
    void reportsErrorsWithPosition() {
        RuleParseException missingValue = assertThrows(RuleParseException.class,
            () -> RuleParser.parse("Individual: @A*\n  Types:"));
        assertEquals(2, missingValue.line());

        assertMessage("Types: Sensor", "must start with");
        assertMessage("", "empty");
        assertMessage("Class: @A* Facts: p @B*", "cannot be used in a Class");
        assertMessage("Class: @A* SubClassOf: hasPart some Sensor", "not supported yet");
        assertMessage("Class: @A* SubClassOf: (A and B)", "not supported yet");
        assertMessage("Individual: @A* Types: A B", "Separate items with commas");
        assertMessage("Individual: @A5x", "Invalid cell reference");
        assertMessage("Individual: @A* Facts: p @B*(xsd:int", "Missing ')'");
        assertMessage("DataProperty: @A* Characteristics: Transitive", "must be one of Functional");
        assertMessage("Individual: \"x\"", "cannot be a \"literal\"");
        assertMessage("Individual: @A* Class: @B*", "Only one entity per rule");
        assertMessage("Individual: @A* Facts: p \"open", "Unclosed string");
    }

    @Test
    void resolvesRuleRangesWithPlusMeaningEnd() {
        SpreadsheetWorkbook.Sheet sheet = new SpreadsheetWorkbook.Sheet("Readings", 21, 7, List.of());
        RuleSpec spec = new RuleSpec("Readings", "A", "A", "2", "+", SENSOR_RULE, null, null);

        assertEquals(new RuleSpec.Range(1, 1, 2, 21), spec.resolve(sheet));
        assertTrue(spec.isActive());
        assertEquals(new RuleSpec.Range(1, 7, 1, 21),
            new RuleSpec("Readings", "A", "+", "1", "+", "", null, true).resolve(sheet));
        assertThrows(IllegalArgumentException.class,
            () -> new RuleSpec("Readings", "C", "A", "2", "+", "", null, true).resolve(sheet));
        assertThrows(IllegalArgumentException.class,
            () -> new RuleSpec("Readings", "A", "A", "0", "+", "", null, true).resolve(sheet));
        assertTrue(new RuleSpec("Readings", "A", "A", "30", "+", "", null, true).resolve(sheet).isEmpty(),
            "start beyond the data with + end yields an empty range, not an error");
    }

    private static void assertMessage(String rule, String expectedFragment) {
        RuleParseException e = assertThrows(RuleParseException.class, () -> RuleParser.parse(rule), rule);
        assertTrue(e.getMessage().contains(expectedFragment),
            "Expected '" + expectedFragment + "' in: " + e.getMessage());
    }
}
