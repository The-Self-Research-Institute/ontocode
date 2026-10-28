package self.research.ontology.common.rules;

import org.junit.jupiter.api.Test;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.model.OWLOntology;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RuleFactsBenchmarkTest {

    @Test
    void owlFactsBenchmark() throws Exception {
        assumeTrue("true".equals(System.getProperty("bench")));
        boolean legacy = "true".equals(System.getProperty("benchLegacy"));
        String sizes = System.getProperty("benchSizes", "100,1000,5000");
        RuleExecutionLimits limits = new RuleExecutionLimits(20, 300_000L, 500_000, () -> false);
        for (String s : sizes.split(",")) {
            int n = Integer.parseInt(s.trim());
            String only = System.getProperty("benchSet", "");
            for (String[] set : new String[][]{{"single", RuleFactsGenerator.SINGLE_RULE}, {"demo", RuleFactsGenerator.DEMO_RULES}}) {
                if (!only.isEmpty() && !only.equals(set[0])) {
                    continue;
                }
                OWLOntology o = RuleExecutionTest.load(RuleFactsGenerator.generate(n, set[1]));
                long t = System.nanoTime();
                RuleExecutionResult r = legacy
                        ? new LegacyRuleExecution(new MiniRuleEngine(), x -> new ReasonerFactory().createReasoner(x), "HermiT").run(o, "b", limits)
                        : RuleExecutionTest.execution(new MiniRuleEngine()).run(o, "b", limits);
                System.out.println("BENCH " + (legacy ? "old" : "new") + " " + set[0] + " n=" + n + " ms=" + (System.nanoTime() - t) / 1_000_000L
                        + " derived=" + r.derived().size() + " rounds=" + r.stopping().rounds() + " status=" + r.status());
            }
        }
    }
}
