package self.research.ontology.common.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import self.research.ontology.common.admission.CapacityConfig.Origin;

class CapacityConfigTest {

    private static java.util.function.Function<String, String> of(Map<String, String> map) {
        return map::get;
    }

    @Test
    void defaultsMatchTheSpecAndAreLabelledDefault() {
        CapacityConfig c = CapacityConfig.defaults();
        AdmissionProperties p = c.admission();
        assertEquals(0.25, p.headroomFraction());
        assertEquals(0.75, p.heapAfterGcRefuseRatio());
        assertEquals(0.85, p.containerRefuseRatio());
        assertEquals(Duration.ofSeconds(5), p.maxWait());
        assertEquals(20, p.maxWaiters());
        assertEquals(1, p.maxActivePerOwner());
        assertEquals(1, p.maxWaitingPerOwner());
        assertEquals(Duration.ofMinutes(30), p.watchdogMaxAge());
        assertEquals(0.40, c.kind(WorkKind.EXECUTE_RULES).maxShare());
        assertEquals(0.30, c.kind(WorkKind.INFERENCE_BUILD).maxShare());
        assertEquals(0.30, c.kind(WorkKind.DL_QUERY).maxShare());
        assertEquals(0.40, c.kind(WorkKind.REASONING).maxShare());
        assertEquals(0.35, c.kind(WorkKind.IMPORT_PARSE).maxShare());
        assertEquals(0.25, c.kind(WorkKind.SPARQL_QUERY).maxShare());
        assertEquals(0.15, c.kind(WorkKind.EXPORT).maxShare());
        assertEquals(0.15, c.kind(WorkKind.SWRL_RULE_RUN).maxShare());
        assertEquals("default", c.sources().get("headroom-fraction").toString());
        assertEquals(Origin.DEFAULT, c.sources().get("kind.execute-rules.max-share").origin());
        assertEquals("0.4", c.effectiveValues().get("kind.execute-rules.max-share"));
        assertEquals(WorkKind.values().length, 11);
    }

    @Test
    void propertyEnvironmentAndAliasWithPrecedenceAndSources() {
        Map<String, String> props = Map.of(
                "ontocode.capacity.headroom-fraction", "0.10",
                "ontocode.capacity.kind.execute-rules.max-share", "0.5",
                "ontocode.admission.maxWaiters", "7",
                "ontocode.admission.containerRefuseRatio", "0.80");
        Map<String, String> env = Map.of(
                "ONTOCODE_CAPACITY_CONTAINER_REFUSE_RATIO", "0.9",
                "ONTOCODE_CAPACITY_KIND_SPARQL_QUERY_MAX_ACTIVE_PER_OWNER", "4",
                "LEGACY_WAIT_MS", "1234");
        CapacityConfig c = CapacityConfig.load(of(props), of(env), Map.of("LEGACY_WAIT_MS", "max-wait-millis"));
        assertEquals(0.10, c.admission().headroomFraction());
        assertEquals(CapacityConfig.Origin.PROPERTY, c.sources().get("headroom-fraction").origin());
        assertEquals("ontocode.capacity.headroom-fraction", c.sources().get("headroom-fraction").name());
        assertEquals(0.5, c.kind(WorkKind.EXECUTE_RULES).maxShare());
        assertEquals(0.9, c.admission().containerRefuseRatio());
        assertEquals(Origin.ENVIRONMENT, c.sources().get("container-refuse-ratio").origin());
        assertEquals(7, c.admission().maxWaiters());
        assertEquals(Origin.ALIAS, c.sources().get("max-waiters").origin());
        assertEquals("ontocode.admission.maxWaiters", c.sources().get("max-waiters").name());
        assertEquals(4, c.kind(WorkKind.SPARQL_QUERY).maxActivePerOwner());
        assertEquals(Duration.ofMillis(1234), c.admission().maxWait());
        assertEquals(Origin.ALIAS, c.sources().get("max-wait-millis").origin());
        assertEquals("alias:LEGACY_WAIT_MS", c.sources().get("max-wait-millis").toString());
    }

    @Test
    void invalidValuesAreIgnoredAndFallBackToTheNextSource() {
        Map<String, String> props = Map.of(
                "ontocode.capacity.headroom-fraction", "5",
                "ontocode.capacity.max-waiters", "many",
                "ontocode.capacity.kind.reasoning.max-share", "0");
        Map<String, String> env = Map.of("ONTOCODE_CAPACITY_HEADROOM_FRACTION", "0.3");
        CapacityConfig c = CapacityConfig.load(of(props), of(env), Map.of());
        assertEquals(0.3, c.admission().headroomFraction());
        assertEquals(Origin.ENVIRONMENT, c.sources().get("headroom-fraction").origin());
        assertEquals(20, c.admission().maxWaiters());
        assertEquals(Origin.DEFAULT, c.sources().get("max-waiters").origin());
        assertEquals(0.40, c.kind(WorkKind.REASONING).maxShare());
    }

    @Test
    void environmentNamesAndKindLookup() {
        assertEquals("ONTOCODE_CAPACITY_KIND_EXECUTE_RULES_MAX_SHARE",
                CapacityConfig.environmentName("kind.execute-rules.max-share"));
        assertEquals("execute-rules", WorkKind.EXECUTE_RULES.configKey());
        CapacityConfig c = CapacityConfig.defaults();
        assertEquals(1.0, c.settingsFor("something-else").maxShare());
        assertEquals(0.25, c.settingsFor("SPARQL_QUERY").maxShare());
        assertTrue(WorkKind.EXECUTE_RULES.services().contains("swrl-service"));
        assertEquals("ruleExecute", WorkKind.EXECUTE_RULES.estimateModel());
    }

    @Test
    void admissionPropertiesLoadFromLookupWithFallbacks() {
        AdmissionProperties p = AdmissionProperties.from(Map.of("maxWaiters", "3", "headroomFraction", "bad")::get);
        assertEquals(3, p.maxWaiters());
        assertEquals(0.25, p.headroomFraction());
        assertEquals("ONTOCODE_ADMISSION_HEADROOM_FRACTION", AdmissionProperties.environmentName("headroomFraction"));
    }

    @Test
    void estimatesAreSaturatingAndOverridable() {
        Estimates e = new Estimates();
        assertEquals(1_000_000, e.loadAndReason(1000));
        assertEquals(3_000, e.importParse(1000));
        assertEquals(Estimates.DEFAULT_SPARQL_ROWS_BYTES_CAP, e.sparqlRows(Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, e.loadAndReason(Long.MAX_VALUE));
        assertEquals(0, e.loadAndReason(-5));
        assertEquals(500_000, e.withEstimatedBytesPerTripleLoad(500).loadAndReason(1000));
    }
}
