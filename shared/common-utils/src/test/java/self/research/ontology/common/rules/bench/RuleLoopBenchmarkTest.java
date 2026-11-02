package self.research.ontology.common.rules;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLRule;
import self.research.ontology.common.RuleStage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RuleLoopBenchmarkTest {

    static final long SKIP_ABOVE = 500_000_000L;
    static final int BASE_INDIVIDUALS = 1000;
    static final int BASE_ASSERTIONS = 1;
    static final int BASE_RULES = 10;
    static final int BASE_DEPTH = 1;

    record Shape(int individuals, int assertions, int rules, int depth, int stages) {
    }

    record Row(String dimension, String setting, String wallMs, String rounds, String derived, String status, String heapBefore, String heapAfter, String engineCalls) {
        String line() {
            return "| " + dimension + " | " + setting + " | " + wallMs + " | " + rounds + " | " + derived + " | " + status + " | "
                    + heapBefore + " | " + heapAfter + " | " + engineCalls + " |";
        }
    }

    static String generate(Shape s) {
        StringBuilder b = new StringBuilder();
        b.append("Prefix(:=<http://example.org/bench#>)\nPrefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)\n")
                .append("Prefix(swrlb:=<http://www.w3.org/2003/11/swrlb#>)\nOntology(<http://example.org/bench>\n");
        b.append("Declaration(DataProperty(:val))\nDeclaration(ObjectProperty(:knows))\n");
        int n = s.individuals();
        for (int i = 0; i < n; i++) {
            b.append("Declaration(NamedIndividual(:i").append(i).append("))\n");
            b.append("DataPropertyAssertion(:val :i").append(i).append(" \"").append(i % 50).append("\"^^xsd:integer)\n");
            for (int j = 1; j <= s.assertions(); j++) {
                b.append("ObjectPropertyAssertion(:knows :i").append(i).append(" :i").append((i + j) % n).append(")\n");
            }
        }
        if (s.stages() > 0) {
            for (int i = 0; i < n; i++) {
                b.append("ClassAssertion(:S0 :i").append(i).append(")\n");
            }
            b.append("Declaration(Class(:S0))\n");
            for (int j = 0; j < s.stages(); j++) {
                b.append("Declaration(Class(:M").append(j).append("))Declaration(Class(:B").append(j).append("))Declaration(Class(:S").append(j + 1).append("))\n");
                b.append("SubClassOf(:M").append(j).append(" ObjectUnionOf(:S").append(j + 1).append(" :B").append(j).append("))\n");
                b.append("DisjointClasses(:M").append(j).append(" :B").append(j).append(")\n");
                b.append("DLSafeRule(Body(ClassAtom(:S").append(j).append(" Variable(:x)) DataPropertyAtom(:val Variable(:x) Variable(:v)) ")
                        .append("BuiltInAtom(swrlb:add Variable(:w) Variable(:v) \"1\"^^xsd:integer) BuiltInAtom(swrlb:greaterThan Variable(:w) \"0\"^^xsd:integer)) ")
                        .append("Head(ClassAtom(:M").append(j).append(" Variable(:x))))\n");
            }
        } else {
            for (int r = 0; r < s.rules(); r++) {
                b.append("Declaration(Class(:D").append(r).append("))\n");
                b.append("DLSafeRule(Body(");
                String prev = "x";
                for (int d = 1; d <= s.depth(); d++) {
                    b.append("ObjectPropertyAtom(:knows Variable(:").append(prev).append(") Variable(:y").append(d).append(")) ");
                    prev = "y" + d;
                }
                b.append("DataPropertyAtom(:val Variable(:").append(prev).append(") Variable(:v)) ")
                        .append("BuiltInAtom(swrlb:add Variable(:w) Variable(:v) \"").append(r + 1).append("\"^^xsd:integer)) ")
                        .append("Head(ClassAtom(:D").append(r).append(" Variable(:x))))\n");
            }
        }
        b.append(")\n");
        return b.toString();
    }

    static long estimate(Shape s) {
        long n = s.individuals();
        long k = s.assertions();
        long facts = n * (k + 1);
        long rules = s.stages() > 0 ? s.stages() : s.rules();
        long depth = s.stages() > 0 ? 1 : s.depth();
        long sum = 1;
        long power = 1;
        for (int d = 0; d <= depth; d++) {
            sum += n * power;
            power *= Math.max(1, k);
        }
        return 2 * rules * facts * sum;
    }

    static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    static String mb(long bytes) {
        return String.format("%.0f", bytes / 1048576.0);
    }

    static Row measure(String dimension, String setting, Shape shape, RuleExecutionLimits limits) throws Exception {
        long est = estimate(shape);
        if (est > SKIP_ABOVE) {
            return new Row(dimension, setting, "skipped", "-", "-", "SKIPPED (estimated " + String.format("%.1e", (double) est) + " steps in the test engine)", "-", "-", "-");
        }
        OWLOntology ontology = RuleExecutionTest.load(generate(shape));
        MiniRuleEngine engine = new MiniRuleEngine();
        long before = usedHeap();
        long start = System.nanoTime();
        RuleExecutionResult result = RuleExecutionTest.execution(engine).run(ontology, "bench", limits);
        long wall = (System.nanoTime() - start) / 1_000_000L;
        Runtime rt = Runtime.getRuntime();
        long after = rt.totalMemory() - rt.freeMemory();
        System.out.println("progress " + dimension + " " + setting + " " + wall + "ms");
        return new Row(dimension, setting, String.valueOf(wall), String.valueOf(result.stopping().rounds()), String.valueOf(result.derived().size()),
                result.status().name(), mb(before), mb(after), String.valueOf(engine.calls.get()));
    }

    static RuleExecutionLimits bigLimits() {
        return new RuleExecutionLimits(20, 600_000L, 500_000, () -> false);
    }

    static final class CancelAfterFirstCall implements RuleStage {
        final MiniRuleEngine inner = new MiniRuleEngine();
        final AtomicBoolean flag = new AtomicBoolean();

        @Override
        public Optional<Set<OWLAxiom>> infer(OWLOntology ontology, Collection<SWRLRule> rules) {
            Optional<Set<OWLAxiom>> out = inner.infer(ontology, rules);
            flag.set(true);
            return out;
        }
    }

    @Test
    void benchmark() throws Exception {
        assumeTrue("true".equals(System.getProperty("bench")));
        List<Row> rows = new ArrayList<>();
        rows.add(measure("baseline", "n=1000, 1 link/ind, 10 rules, depth 1",
                new Shape(BASE_INDIVIDUALS, BASE_ASSERTIONS, BASE_RULES, BASE_DEPTH, 0), bigLimits()));
        for (int n : new int[]{100, 1000, 5000}) {
            rows.add(measure("individuals", String.valueOf(n), new Shape(n, BASE_ASSERTIONS, BASE_RULES, BASE_DEPTH, 0), bigLimits()));
        }
        for (int k : new int[]{1, 5, 20}) {
            rows.add(measure("assertions per individual", k + " links + 1 value", new Shape(BASE_INDIVIDUALS, k, BASE_RULES, BASE_DEPTH, 0), bigLimits()));
        }
        for (int r : new int[]{1, 10, 50}) {
            rows.add(measure("rules", String.valueOf(r), new Shape(BASE_INDIVIDUALS, BASE_ASSERTIONS, r, BASE_DEPTH, 0), bigLimits()));
        }
        for (int d : new int[]{1, 3, 6}) {
            rows.add(measure("join depth", d + " link hops", new Shape(BASE_INDIVIDUALS, BASE_ASSERTIONS, BASE_RULES, d, 0), bigLimits()));
        }
        for (int st : new int[]{1, 3, 6}) {
            rows.add(measure("derived growth by rounds", st + " rule/OWL stages, n=200", new Shape(200, BASE_ASSERTIONS, 0, 1, st), bigLimits()));
        }

        Shape cancelShape = new Shape(200, BASE_ASSERTIONS, 0, 1, 6);
        OWLOntology full = RuleExecutionTest.load(generate(cancelShape));
        long t0 = System.nanoTime();
        RuleExecutionResult fullRun = RuleExecutionTest.execution(new MiniRuleEngine()).run(full, "bench", bigLimits());
        long fullMs = (System.nanoTime() - t0) / 1_000_000L;
        CancelAfterFirstCall stage = new CancelAfterFirstCall();
        OWLOntology cancelled = RuleExecutionTest.load(generate(cancelShape));
        long t1 = System.nanoTime();
        RuleExecutionResult cancelRun = RuleExecutionTest.execution(stage).run(cancelled, "bench", bigLimits().withCancel(stage.flag::get));
        long cancelMs = (System.nanoTime() - t1) / 1_000_000L;
        rows.add(new Row("cancellation", "uncancelled, 6 stages, n=200", String.valueOf(fullMs), String.valueOf(fullRun.stopping().rounds()),
                String.valueOf(fullRun.derived().size()), fullRun.status().name(), "-", "-", "-"));
        rows.add(new Row("cancellation", "cancelled after first round", String.valueOf(cancelMs), String.valueOf(cancelRun.stopping().rounds()),
                String.valueOf(cancelRun.derived().size()), cancelRun.status().name(), "-", "-", String.valueOf(stage.inner.calls.get())));

        int threads = 4;
        Shape concurrentShape = new Shape(500, BASE_ASSERTIONS, BASE_RULES, BASE_DEPTH, 0);
        List<OWLOntology> ontologies = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            ontologies.add(RuleExecutionTest.load(generate(concurrentShape)));
        }
        long s0 = System.nanoTime();
        for (OWLOntology o : ontologies) {
            RuleExecutionTest.execution(new MiniRuleEngine()).run(o, "bench", bigLimits());
        }
        long sequentialMs = (System.nanoTime() - s0) / 1_000_000L;
        List<OWLOntology> fresh = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fresh.add(RuleExecutionTest.load(generate(concurrentShape)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<RuleExecutionResult>> futures = new ArrayList<>();
        long c0 = System.nanoTime();
        for (OWLOntology o : fresh) {
            futures.add(pool.submit(() -> RuleExecutionTest.execution(new MiniRuleEngine()).run(o, "bench", bigLimits())));
        }
        int derivedTotal = 0;
        StringBuilder statuses = new StringBuilder();
        for (Future<RuleExecutionResult> f : futures) {
            RuleExecutionResult r = f.get();
            derivedTotal += r.derived().size();
            statuses.append(r.status().name()).append(' ');
        }
        long concurrentMs = (System.nanoTime() - c0) / 1_000_000L;
        pool.shutdown();
        rows.add(new Row("concurrency", "4 ontologies (n=500) one after another", String.valueOf(sequentialMs), "-", "-", "-", "-", "-", "-"));
        rows.add(new Row("concurrency", "4 ontologies (n=500) in 4 threads", String.valueOf(concurrentMs), "-", String.valueOf(derivedTotal), statuses.toString().trim(), "-", "-", "-"));

        StringBuilder md = new StringBuilder();
        md.append("| dimension | setting | wall ms | rounds | derived | status | heap MB before | heap MB after | engine calls |\n");
        md.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            md.append(r.line()).append('\n');
        }
        System.out.println(md);
        Path out = Path.of("target", "bench-results.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString(), StandardCharsets.UTF_8);
    }
}
