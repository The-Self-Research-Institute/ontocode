package self.research.ontology.common.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleNamesTest {

    private static final String DECLS = "Declaration(Class(:A))\nDeclaration(Class(:B))\nDeclaration(Class(:C))\nDeclaration(NamedIndividual(:a))\nClassAssertion(:A :a)\n";

    private static RuleExecutionResult runBody(String rules) throws Exception {
        return RuleExecutionTest.run(RuleExecutionTest.inline(DECLS + rules), new MiniRuleEngine());
    }

    @Test
    void aLabelledRuleIsReportedByItsLabel() throws Exception {
        RuleExecutionResult r = runBody("DLSafeRule(Annotation(rdfs:label \"Adults are people\") Body(ClassAtom(:A Variable(:x))) Head(ClassAtom(:B Variable(:x))))\n");
        assertEquals(List.of("Adults are people"), r.executedRules());
        assertEquals(List.of("Adults are people"), r.executedRuleIds());
    }

    @Test
    void anUnlabelledRuleIsReportedAsRuleAndEightHexDigitsWhileTheIdKeepsItsForm() throws Exception {
        RuleExecutionResult r = runBody("DLSafeRule(Body(ClassAtom(:A Variable(:x))) Head(ClassAtom(:B Variable(:x))))\n");
        assertEquals(1, r.executedRules().size());
        assertTrue(r.executedRules().get(0).matches("Rule [0-9a-f]{8}"), r.executedRules().get(0));
        assertFalse(r.executedRules().get(0).contains("rule-"));
        assertTrue(r.executedRuleIds().get(0).matches("rule-[0-9a-f]{8}"));
        assertEquals(r.executedRules().get(0).substring(5), r.executedRuleIds().get(0).substring(5));
    }

    @Test
    void duplicateLabelsAreBothReportedAndTheListsStayParallel() throws Exception {
        RuleExecutionResult r = runBody(
                "DLSafeRule(Annotation(rdfs:label \"Same\") Body(ClassAtom(:A Variable(:x))) Head(ClassAtom(:B Variable(:x))))\n"
                        + "DLSafeRule(Annotation(rdfs:label \"Same\") Body(ClassAtom(:A Variable(:x))) Head(ClassAtom(:C Variable(:x))))\n");
        assertEquals(List.of("Same", "Same"), r.executedRules());
        assertEquals(r.executedRules().size(), r.executedRuleIds().size());
    }

    @Test
    void skippedRulesAreReportedByNameWithTheIdKept() throws Exception {
        RuleExecutionResult r = RuleExecutionTest.run(RuleExecutionTest.inline(DECLS
                + "DLSafeRule(Body(ClassAtom(:A Variable(:x)) BuiltInAtom(swrlb:equal Variable(:x) Variable(:x))) Head(SameIndividualAtom(Variable(:x) Variable(:x))))\n"), null);
        assertEquals(1, r.skippedRules().size());
        assertTrue(r.skippedRules().get(0).ruleName().matches("Rule [0-9a-f]{8}"));
        assertTrue(r.skippedRules().get(0).ruleId().matches("rule-[0-9a-f]{8}"));
    }
}
