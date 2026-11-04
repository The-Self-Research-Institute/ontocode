package self.research.ontology.owlEditor.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import self.research.ontology.common.admission.CapacityConfig;
import self.research.ontology.common.admission.Estimates;
import self.research.ontology.common.admission.WorkKind;
import self.research.ontology.owlEditor.service.SparqlResultLimits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapacityConfigurationTest {

    private final CapacityConfiguration configuration = new CapacityConfiguration();

    @Test
    void defaultsApplyWhenNothingIsSet() {
        CapacityConfig config = configuration.capacityConfig(new MockEnvironment());
        assertEquals(1, config.kind(WorkKind.DL_QUERY).maxActivePerOwner());
        assertEquals("default", config.sources().get("kind.dl-query.max-active-per-owner").toString());
    }

    @Test
    void theNewPropertyWinsAndIsReportedAsAProperty() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("ontocode.capacity.headroom-fraction", "0.1")
                .withProperty("ontocode.capacity.kind.execute-rules.max-share", "0.2");
        CapacityConfig config = configuration.capacityConfig(env);
        assertEquals(0.1, config.admission().headroomFraction(), 1e-9);
        assertEquals(0.2, config.kind(WorkKind.EXECUTE_RULES).maxShare(), 1e-9);
        assertTrue(config.sources().get("headroom-fraction").toString().startsWith("property:"));
    }

    @Test
    void legacyNamesAreAliasesAndShowTheirSource() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("ontocode.dlquery.max-concurrent", "3")
                .withProperty("ontocode.import.max-concurrent", "2");
        CapacityConfig config = configuration.capacityConfig(env);
        assertEquals(3, config.kind(WorkKind.DL_QUERY).maxActivePerOwner());
        assertEquals(2, config.kind(WorkKind.IMPORT_PARSE).maxActivePerOwner());
        assertEquals("alias:ontocode.dlquery.max-concurrent",
                config.sources().get("kind.dl-query.max-active-per-owner").toString());
    }

    @Test
    void theNewNameBeatsTheLegacyAlias() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("ontocode.dlquery.max-concurrent", "3")
                .withProperty("ontocode.capacity.kind.dl-query.max-active-per-owner", "4");
        CapacityConfig config = configuration.capacityConfig(env);
        assertEquals(4, config.kind(WorkKind.DL_QUERY).maxActivePerOwner());
    }

    @Test
    void sparqlLimitsAndEstimatesComeFromTheSameNamespace() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("ontocode.capacity.sparql.max-rows", "50")
                .withProperty("ontocode.capacity.sparql.max-result-bytes", "4096")
                .withProperty("ontocode.capacity.estimate.bytes-per-triple-load", "500");
        Estimates estimates = configuration.capacityEstimates(env);
        SparqlResultLimits limits = configuration.sparqlResultLimits(env, estimates);
        assertEquals(50, limits.maxRows());
        assertEquals(4096L, limits.maxBytes());
        assertEquals(500L, estimates.estimatedBytesPerTripleLoad());
        assertEquals(SparqlResultLimits.DEFAULT_MAX_ROWS,
                configuration.sparqlResultLimits(new MockEnvironment(), new Estimates()).maxRows());
    }

    @Test
    void invalidValuesFallBackToDefaults() {
        MockEnvironment env = new MockEnvironment().withProperty("ontocode.capacity.sparql.max-rows", "-5");
        assertEquals(SparqlResultLimits.DEFAULT_MAX_ROWS,
                configuration.sparqlResultLimits(env, new Estimates()).maxRows());
    }
}
