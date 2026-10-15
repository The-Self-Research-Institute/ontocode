package self.research.ontology.owlEditor.service.spreadsheet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.SpreadsheetRuleSetEntity;
import self.research.ontology.owlEditor.repository.SpreadsheetRuleSetRepository;
import self.research.ontology.owlEditor.service.spreadsheet.rule.EntityKind;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleSpec;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpreadsheetRuleSetServiceTest {

    private static final String NS = "http://example.org/sensor#";
    private static final RuleSpec SENSOR_RULE = new RuleSpec("readings", "A", "A", "2", "+",
        "Individual: @A* Types: SensorReading Facts: hasMeasurementValue @B*(xsd:decimal)", "one per row", true);

    private final Map<String, SpreadsheetRuleSetEntity> store = new HashMap<>();
    private SpreadsheetRuleSetService service;

    @BeforeEach
    void setUp() {
        SpreadsheetRuleSetRepository repo = mock(SpreadsheetRuleSetRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            SpreadsheetRuleSetEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID().toString());
            }
            store.put(e.getId(), e);
            return e;
        });
        when(repo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.<String>getArgument(0))));
        when(repo.findByProjectIdAndName(anyString(), anyString())).thenAnswer(inv -> store.values().stream()
            .filter(e -> e.getProjectId().equals(inv.getArgument(0)) && e.getName().equals(inv.getArgument(1)))
            .findFirst());
        doAnswer(inv -> store.remove(inv.<SpreadsheetRuleSetEntity>getArgument(0).getId()))
            .when(repo).delete(any(SpreadsheetRuleSetEntity.class));
        service = new SpreadsheetRuleSetService(repo);
    }

    @Test
    void savesUpdatesAndDeletesWithinOneProject() {
        SpreadsheetRuleSetEntity saved = service.create("p1", "  Sensor readings ", null, List.of(SENSOR_RULE), "kay");
        assertEquals("Sensor readings", saved.getName(), "name is trimmed");
        assertTrue(service.get("p1", saved.getId()).isPresent());
        assertTrue(service.get("other-project", saved.getId()).isEmpty(), "rule sets are scoped to their project");

        assertThrows(SpreadsheetRuleSetService.DuplicateNameException.class,
            () -> service.create("p1", "Sensor readings", null, List.of(SENSOR_RULE), "kay"));
        service.create("p2", "Sensor readings", null, List.of(SENSOR_RULE), "kay");

        SpreadsheetRuleSetEntity updated = service.update("p1", saved.getId(), "Sensor readings v2", "monthly drop",
            List.of(SENSOR_RULE, SENSOR_RULE), "kay").orElseThrow();
        assertEquals(2, updated.getRules().size());
        assertEquals("monthly drop", updated.getDescription());

        assertFalse(service.delete("other-project", saved.getId()));
        assertTrue(service.delete("p1", saved.getId()));
        assertTrue(service.get("p1", saved.getId()).isEmpty());
    }

    @Test
    void rejectsEmptyNamesAndEmptyRuleSets() {
        assertThrows(IllegalArgumentException.class, () -> service.create("p1", " ", null, List.of(SENSOR_RULE), "kay"));
        assertThrows(IllegalArgumentException.class, () -> service.create("p1", "x", null, List.of(), "kay"));
        assertTrue(service.update("p1", "missing", "x", null, List.of(SENSOR_RULE), "kay").isEmpty());
    }

    @Test
    void savedRulesReRunAgainstALongerFileOfTheSameShape() {
        SpreadsheetRuleSetEntity saved = service.create("p1", "Sensor readings", null, List.of(SENSOR_RULE), "kay");
        List<RuleSpec> reloaded = service.get("p1", saved.getId()).orElseThrow().getRules();

        GenerationResult september = AxiomGenerator.generate(csv(20), reloaded, lookup());
        GenerationResult october = AxiomGenerator.generate(csv(35), reloaded, lookup());

        assertFalse(september.hasErrors(), () -> september.issues().toString());
        assertFalse(october.hasErrors(), () -> october.issues().toString());
        assertEquals(20, september.rowsProcessed());
        assertEquals(35, october.rowsProcessed(), "End row '+' picks up the extra rows");
        assertEquals(35, october.ops().stream().filter(o -> o.type().equals("createIndividual")).count());
    }

    private static SpreadsheetWorkbook csv(int rows) {
        StringBuilder sb = new StringBuilder("Id,Value\n");
        for (int i = 1; i <= rows; i++) {
            sb.append("S").append(i).append(",").append(20 + i / 10.0).append("\n");
        }
        return new SpreadsheetParserService().parse(
            new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)), "readings.csv");
    }

    private static OntologyLookup lookup() {
        Map<String, OntologyLookup.KnownEntity> known = Map.of(
            NS + "SensorReading", new OntologyLookup.KnownEntity(NS + "SensorReading", Set.of(EntityKind.CLASS), null),
            NS + "hasMeasurementValue", new OntologyLookup.KnownEntity(NS + "hasMeasurementValue",
                Set.of(EntityKind.DATA_PROPERTY), "http://www.w3.org/2001/XMLSchema#decimal"));
        return new OntologyLookup() {
            public String defaultNamespace() {
                return NS;
            }

            public Map<String, String> prefixes() {
                return Map.of();
            }

            public Optional<KnownEntity> byIri(String iri) {
                return Optional.ofNullable(known.get(iri));
            }

            public List<KnownEntity> byLabel(String label) {
                return List.of();
            }
        };
    }
}
