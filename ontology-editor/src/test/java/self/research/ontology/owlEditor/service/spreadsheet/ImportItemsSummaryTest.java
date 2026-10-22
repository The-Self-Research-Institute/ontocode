package self.research.ontology.owlEditor.service.spreadsheet;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportItemsSummaryTest {

    private static final String TYPE = "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>";
    private static final String LABEL = "<http://www.w3.org/2000/01/rdf-schema#label>";
    private static final String COMMENT = "<http://www.w3.org/2000/01/rdf-schema#comment>";
    private static final String SUBCLASS = "<http://www.w3.org/2000/01/rdf-schema#subClassOf>";
    private static final String OWL = "http://www.w3.org/2002/07/owl#";
    private static final String DECIMAL = "^^<http://www.w3.org/2001/XMLSchema#decimal>";

    private static String type(String s, String owlKind) {
        return "<http://ex/s#" + s + "> " + TYPE + " <" + OWL + owlKind + ">";
    }

    private static String t(String s, String p, String o) {
        return "<http://ex/s#" + s + "> <http://ex/s#" + p + "> " + o;
    }

    private static List<String> sensorImport() {
        return List.of(
            type("TemperatureReading", "Class"),
            "<http://ex/s#TemperatureReading> " + SUBCLASS + " <http://ex/s#SensorReading>",
            type("R101", "NamedIndividual"),
            type("R102", "NamedIndividual"),
            "<http://ex/s#R101> " + TYPE + " <http://ex/s#TemperatureReading>",
            "<http://ex/s#R101> " + LABEL + " \"Temperature reading 101\"@en",
            "<http://ex/s#R102> " + LABEL + " \"Humidity reading 102\"",
            type("measuredAt", "ObjectProperty"),
            type("hasAccuracy", "DatatypeProperty"),
            type("dataSource", "AnnotationProperty"),
            t("R101", "measuredAt", "<http://ex/s#LabA>"),
            t("R101", "comparedWith", "<http://ex/s#R102>"),
            t("R101", "hasAccuracy", "\"0.5\"" + DECIMAL),
            t("R101", "dataSource", "\"sheet 1\""),
            "<http://ex/s#R101> " + COMMENT + " \"checked\"",
            t("LabA", "hasRegion", "\"South\""));
    }

    @Test
    void groupsNewEntitiesByKindAndPrefersLabels() {
        Map<String, ImportItemsSummary.Group> s = ImportItemsSummary.of(sensorImport(), 50);

        assertEquals(List.of("TemperatureReading"), names(s, "classes"));
        assertEquals(List.of("Temperature reading 101", "Humidity reading 102"), names(s, "individuals"));
        assertEquals(List.of("measuredAt"), names(s, "objectProperties"));
        assertEquals(List.of("hasAccuracy"), names(s, "dataProperties"));
        assertEquals(List.of("dataSource"), names(s, "annotationProperties"));
        assertEquals(0, s.get("datatypes").total());
    }

    @Test
    void individualsCarryTheirAssertionsByKind() {
        ImportItemsSummary.Item r101 = ImportItemsSummary.of(sensorImport(), 50).get("individuals").items().get(0);
        ImportItemsSummary.Assertions a = r101.assertions();

        assertEquals(List.of("TemperatureReading"), a.classes());
        assertEquals(List.of("measuredAt → LabA", "comparedWith → Humidity reading 102"),
            a.objects().stream().map(l -> l.property() + " → " + l.target()).toList());
        assertEquals(List.of("hasAccuracy = 0.5"),
            a.data().stream().map(v -> v.property() + " = " + v.value()).toList());
        assertEquals(List.of("dataSource = sheet 1", "comment = checked"),
            a.annotations().stream().map(v -> v.property() + " = " + v.value()).toList());
    }

    @Test
    void classesAndPropertiesHaveNoAssertions() {
        Map<String, ImportItemsSummary.Group> s = ImportItemsSummary.of(sensorImport(), 50);

        assertNull(s.get("classes").items().get(0).assertions());
        assertNull(s.get("objectProperties").items().get(0).assertions());
    }

    @Test
    void valuesAddedToExistingIndividualsAreListedSeparately() {
        ImportItemsSummary.Group updated = ImportItemsSummary.of(sensorImport(), 50)
            .get(ImportItemsSummary.UPDATED_INDIVIDUALS);

        assertEquals(1, updated.total());
        assertEquals("LabA", updated.items().get(0).name());
        assertEquals("South", updated.items().get(0).assertions().data().get(0).value());
    }

    @Test
    void listsAtMostTheLimitButCountsEverything() {
        List<String> triples = java.util.stream.IntStream.range(0, 120)
            .mapToObj(i -> type("S" + i, "NamedIndividual")).toList();

        ImportItemsSummary.Group individuals = ImportItemsSummary.of(triples, 50).get("individuals");

        assertEquals(120, individuals.total());
        assertEquals(50, individuals.items().size());
    }

    @Test
    void pagesThroughAGroup() {
        List<String> triples = java.util.stream.IntStream.range(0, 120)
            .mapToObj(i -> type("S" + i, "NamedIndividual")).toList();

        ImportItemsSummary.Group next = ImportItemsSummary.page(triples, "individuals", 100, 50);
        ImportItemsSummary.Group past = ImportItemsSummary.page(triples, "individuals", 500, 50);

        assertEquals(100, next.offset());
        assertEquals(20, next.items().size());
        assertEquals("S100", next.items().get(0).name());
        assertTrue(past.items().isEmpty());
        assertNull(ImportItemsSummary.page(triples, "unknown", 0, 50));
    }

    @Test
    void anImportWithOnlyValuesListsTheUpdatedIndividual() {
        Map<String, ImportItemsSummary.Group> s = ImportItemsSummary.of(List.of(
            "<http://ex/s#R1> <http://ex/s#hasValue> \"1\""), 50);

        assertEquals(1, s.get(ImportItemsSummary.UPDATED_INDIVIDUALS).total());
        assertEquals(0, s.get("individuals").total());
        assertEquals(0, s.get("classes").total());
    }

    private static List<String> names(Map<String, ImportItemsSummary.Group> s, String kind) {
        return s.get(kind).items().stream().map(ImportItemsSummary.Item::name).toList();
    }
}
