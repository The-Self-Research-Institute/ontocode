package self.research.ontology.swrl.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.swrlapi.core.SWRLRuleEngine;
import org.swrlapi.factory.SWRLAPIFactory;
import org.swrlapi.sqwrl.SQWRLQueryEngine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Corrected function libraries are installed on every engine that is created")
class BuiltInLibrariesTest {

    private static OWLOntology empty() throws Exception {
        return OWLManager.createOWLOntologyManager().createOntology();
    }

    @Test
    void ruleEnginesKnowTheFunctionsTheStockEngineLeavesOut() throws Exception {
        OWLOntology ontology = empty();
        SWRLRuleEngine stock = SWRLAPIFactory.createSWRLRuleEngine(ontology);
        assertFalse(stock.isSWRLBuiltIn("swrlb:subtractDateTimesYieldingDayTimeDuration"));
        assertFalse(stock.isSWRLBuiltIn("sqwrl:countDistinct"));
        SWRLRuleEngine engine = BuiltInLibraries.createRuleEngine(ontology);
        assertTrue(engine.isSWRLBuiltIn("swrlb:subtractDateTimesYieldingDayTimeDuration"));
        assertTrue(engine.isSWRLBuiltIn("sqwrl:countDistinct"));
        assertTrue(engine.isSWRLBuiltIn("swrlb:add"));
        assertTrue(engine.isSWRLBuiltIn("swrlm:eval"));
        assertTrue(engine.isSWRLBuiltIn("temporal:durationEqualTo"));
    }

    @Test
    void queryEnginesKnowThemToo() throws Exception {
        SQWRLQueryEngine engine = BuiltInLibraries.createQueryEngine(empty());
        assertTrue(engine.isSWRLBuiltIn("sqwrl:countDistinct"));
        assertTrue(engine.isSWRLBuiltIn("swrlb:subtractDateTimesYieldingDayTimeDuration"));
    }

    @Test
    void theInventoryIncludesTheAddedFunctions() {
        var inventory = BuiltInLibraries.libraries().stream()
                .collect(java.util.stream.Collectors.toMap(l -> l.getPrefix(), l -> l.getBuiltInNames()));
        assertTrue(inventory.get("swrlb").contains("subtractDateTimesYieldingDayTimeDuration"));
        assertTrue(inventory.get("sqwrl").contains("countDistinct"));
        assertTrue(inventory.keySet().containsAll(java.util.Set.of("swrlb", "swrlm", "swrlx", "temporal", "sqwrl", "tbox", "abox", "rbox")));
    }
}
