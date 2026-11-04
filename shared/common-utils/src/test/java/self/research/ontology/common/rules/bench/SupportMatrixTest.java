package self.research.ontology.common.rules;

import org.junit.jupiter.api.Test;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportMatrixTest {

    static final Path CASES = Path.of("..", "..", "test-data", "rule-capability");
    static final Path DOC = Path.of("..", "..", "test-data", "rule-capability", "support-matrix.txt");
    static final Set<String> MINI_SUPPORTED = Set.of("add", "greaterThan", "lessThan");
    static final Map<String, String> ENGINE_ALONE = Map.ofEntries(
            Map.entry("01", "ENTAILED"), Map.entry("02", "ENTAILED"), Map.entry("03", "ENTAILED"), Map.entry("04", "ENTAILED"),
            Map.entry("05", "ENTAILED"), Map.entry("06", "ENTAILED"), Map.entry("07", "ENTAILED"), Map.entry("08", "ENTAILED"),
            Map.entry("09", "NOT_ENTAILED"), Map.entry("10", "ENTAILED"), Map.entry("11", "ENTAILED"), Map.entry("12", "ENTAILED"),
            Map.entry("13", "ENTAILED"), Map.entry("14", "ENTAILED"), Map.entry("15", "NOT_ENTAILED"), Map.entry("16", "NOT_ENTAILED"));
    static final Set<String> ENGINE_EVIDENCE = Set.of("add", "greaterThan", "lessThan", "equal", "mod", "contains");
    static final Set<String> TRANSLATED_TO_OWL = Set.of("equal", "notEqual", "lessThan", "lessThanOrEqual", "greaterThan", "greaterThanOrEqual");
    static final List<String[]> BUILT_INS = List.of(
            new String[]{"swrlb", "Compare", "equal"}, new String[]{"swrlb", "Compare", "notEqual"}, new String[]{"swrlb", "Compare", "lessThan"},
            new String[]{"swrlb", "Compare", "lessThanOrEqual"}, new String[]{"swrlb", "Compare", "greaterThan"}, new String[]{"swrlb", "Compare", "greaterThanOrEqual"},
            new String[]{"swrlb", "Math", "add"}, new String[]{"swrlb", "Math", "subtract"}, new String[]{"swrlb", "Math", "multiply"},
            new String[]{"swrlb", "Math", "divide"}, new String[]{"swrlb", "Math", "mod"}, new String[]{"swrlb", "Math", "pow"},
            new String[]{"swrlb", "Math", "abs"}, new String[]{"swrlb", "Math", "ceiling"}, new String[]{"swrlb", "Math", "floor"},
            new String[]{"swrlb", "Math", "round"},
            new String[]{"swrlb", "String", "stringConcat"}, new String[]{"swrlb", "String", "stringLength"}, new String[]{"swrlb", "String", "substring"},
            new String[]{"swrlb", "String", "contains"}, new String[]{"swrlb", "String", "containsIgnoreCase"}, new String[]{"swrlb", "String", "startsWith"},
            new String[]{"swrlb", "String", "endsWith"}, new String[]{"swrlb", "String", "upperCase"}, new String[]{"swrlb", "String", "lowerCase"},
            new String[]{"swrlb", "String", "matches"}, new String[]{"swrlb", "String", "replace"},
            new String[]{"swrlb", "Date and time", "date"}, new String[]{"swrlb", "Date and time", "time"}, new String[]{"swrlb", "Date and time", "dateTime"},
            new String[]{"swrlb", "Date and time", "yearMonthDuration"}, new String[]{"swrlb", "Date and time", "dayTimeDuration"},
            new String[]{"swrlm", "Advanced math", "sqrt"}, new String[]{"swrlm", "Advanced math", "log"}, new String[]{"swrlm", "Advanced math", "exp"},
            new String[]{"swrlm", "Advanced math", "sin"}, new String[]{"swrlm", "Advanced math", "cos"}, new String[]{"swrlm", "Advanced math", "tan"},
            new String[]{"swrlm", "Advanced math", "asin"}, new String[]{"swrlm", "Advanced math", "acos"}, new String[]{"swrlm", "Advanced math", "atan"},
            new String[]{"swrlm", "Advanced math", "toDegrees"}, new String[]{"swrlm", "Advanced math", "toRadians"}, new String[]{"swrlm", "Advanced math", "eval"},
            new String[]{"temporal", "Temporal intervals", "equals"}, new String[]{"temporal", "Temporal intervals", "before"},
            new String[]{"temporal", "Temporal intervals", "after"}, new String[]{"temporal", "Temporal intervals", "meets"},
            new String[]{"temporal", "Temporal intervals", "overlaps"}, new String[]{"temporal", "Temporal intervals", "contains"},
            new String[]{"temporal", "Temporal intervals", "during"}, new String[]{"temporal", "Temporal intervals", "starts"},
            new String[]{"temporal", "Temporal intervals", "finishes"},
            new String[]{"sqwrl", "SQWRL query", "select"}, new String[]{"sqwrl", "SQWRL query", "selectDistinct"}, new String[]{"sqwrl", "SQWRL query", "count"},
            new String[]{"sqwrl", "SQWRL query", "min"}, new String[]{"sqwrl", "SQWRL query", "max"}, new String[]{"sqwrl", "SQWRL query", "sum"},
            new String[]{"sqwrl", "SQWRL query", "avg"}, new String[]{"sqwrl", "SQWRL query", "orderBy"}, new String[]{"sqwrl", "SQWRL query", "orderByDescending"});

    static String hermit(String text, String expect) {
        try {
            OWLOntology ontology = RuleExecutionTest.load(text);
            String header = text.substring(0, text.indexOf("Ontology(")) + "Ontology(<http://example.org/rc-expect>\n";
            OWLOntology expected = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(header + expect + ")\n"));
            OWLAxiom axiom = expected.getLogicalAxioms().iterator().next();
            OWLReasoner reasoner = new ReasonerFactory().createReasoner(ontology);
            try {
                return reasoner.isEntailed(axiom) ? "ENTAILED" : "NOT_ENTAILED";
            } finally {
                reasoner.dispose();
            }
        } catch (Throwable error) {
            return "ERROR:" + error.getClass().getSimpleName();
        }
    }

    static Set<String> builtInsIn(String text) {
        Set<String> names = new TreeSet<>();
        Matcher m = Pattern.compile("BuiltInAtom\\(swrlb:(\\w+)").matcher(text);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    static String loop(String text, String expect, Set<String> builtIns) {
        if (!MINI_SUPPORTED.containsAll(builtIns)) {
            return "not run (built-in outside the test engine)";
        }
        if (text.contains("DifferentIndividualsAtom") || text.contains("ClassAtom(Object")) {
            return "not run (atom outside the test engine)";
        }
        try {
            RuleExecutionResult result = RuleExecutionTest.run(RuleExecutionTest.load(text), new MiniRuleEngine());
            String verdict = result.working() == null ? "ERROR" : hermit(text, expect, result.working());
            return verdict + " (" + result.status().name() + ")";
        } catch (Throwable error) {
            return "ERROR:" + error.getClass().getSimpleName();
        }
    }

    static String hermit(String text, String expect, OWLOntology working) {
        try {
            String header = text.substring(0, text.indexOf("Ontology(")) + "Ontology(<http://example.org/rc-expect>\n";
            OWLOntology expected = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(header + expect + ")\n"));
            OWLAxiom axiom = expected.getLogicalAxioms().iterator().next();
            OWLReasoner reasoner = new ReasonerFactory().createReasoner(working);
            try {
                return reasoner.isEntailed(axiom) ? "ENTAILED" : "NOT_ENTAILED";
            } finally {
                reasoner.dispose();
            }
        } catch (Throwable error) {
            return "ERROR:" + error.getClass().getSimpleName();
        }
    }

    static String status(String hermit, String engine, String loop) {
        if (loop.startsWith("ENTAILED")) {
            return "Supported through the loop";
        }
        if (hermit.equals("ENTAILED")) {
            return "Supported by HermiT alone";
        }
        if (engine.equals("ENTAILED") && loop.startsWith("not run")) {
            return "Supported by the engine; loop not exercised here";
        }
        if (engine.equals("ENTAILED")) {
            return "Supported by the engine only; loop result differs";
        }
        return "Not supported";
    }

    static String generate() throws IOException {
        Properties cases = new Properties();
        cases.load(Files.newBufferedReader(CASES.resolve("cases.properties")));
        TreeMap<String, String[]> rows = new TreeMap<>();
        Set<String> usedInFixtures = new TreeSet<>();
        for (String key : cases.stringPropertyNames()) {
            if (!key.endsWith(".file")) {
                continue;
            }
            String num = key.substring(0, 2);
            String file = cases.getProperty(key);
            String text = Files.readString(CASES.resolve(file), StandardCharsets.UTF_8);
            Set<String> builtIns = builtInsIn(text);
            usedInFixtures.addAll(builtIns);
            String hermit = hermit(text, cases.getProperty(num + ".expect"));
            String engine = ENGINE_ALONE.get(num);
            String loop = loop(text, cases.getProperty(num + ".expect"), builtIns);
            rows.put(num, new String[]{file, cases.getProperty(num + ".title"), hermit, engine, loop, status(hermit, engine, loop)});
        }
        StringBuilder md = new StringBuilder();
        md.append("# Rule support matrix\n\n");
        md.append("This page is generated by `SupportMatrixTest` in `shared/common-utils`. Run `mvn -o test -Dtest=SupportMatrixTest -DupdateMatrix=true` to rewrite it. ");
        md.append("The test fails if the HermiT or loop columns stop matching this file.\n\n");
        md.append("Columns: HermiT alone is plain OWL entailment of the expected conclusion with the rules left in the ontology. ");
        md.append("Custom engine alone is the recorded result of `EngineRuleCapabilityTest` in `ontology-swrl` (not computed here). ");
        md.append("Coordinated loop is `RuleExecution` with HermiT and the in-test `MiniRuleEngine`, which only understands class, object property and data property atoms and the built-ins add, greaterThan and lessThan; ");
        md.append("fixtures needing anything else are marked not run.\n\n");
        md.append("| fixture | rule shape | HermiT alone | custom engine alone | coordinated loop | status |\n");
        md.append("|---|---|---|---|---|---|\n");
        for (String[] r : rows.values()) {
            md.append("| ").append(r[0]).append(" | ").append(r[1]).append(" | ").append(r[2]).append(" | ").append(r[3]).append(" | ")
                    .append(r[4]).append(" | ").append(r[5]).append(" |\n");
        }
        md.append("\n## SWRL built-ins\n\n");
        md.append("The list is the one offered by the SWRL editor plugin. Engine support is marked yes only where a test in `ontology-swrl` runs the built-in through the engine; ");
        md.append("everything else is untested, not known to be unsupported. Tested here means a fixture in `test-data/rule-capability` uses it.\n\n");
        md.append("| built-in | category | supported by engine | tested here | handled by the loop |\n");
        md.append("|---|---|---|---|---|\n");
        for (String[] b : BUILT_INS) {
            boolean swrlb = b[0].equals("swrlb");
            boolean engineYes = swrlb && ENGINE_EVIDENCE.contains(b[2]);
            boolean tested = swrlb && usedInFixtures.contains(b[2]);
            String handled = !swrlb ? "untested" : TRANSLATED_TO_OWL.contains(b[2]) ? "turned into OWL for the simple shape, otherwise sent to the engine" : "sent to the engine";
            md.append("| ").append(b[0]).append(":").append(b[2]).append(" | ").append(b[1]).append(" | ").append(engineYes ? "yes" : "untested")
                    .append(" | ").append(tested ? "yes" : "no").append(" | ").append(handled).append(" |\n");
        }
        return md.toString();
    }

    static String normalize(String text) {
        return text.replace("\r\n", "\n").trim();
    }

    @Test
    void theCommittedMatrixMatchesWhatIsComputedNow() throws IOException {
        String generated = generate();
        if ("true".equals(System.getProperty("updateMatrix"))) {
            Files.createDirectories(DOC.getParent());
            Files.writeString(DOC, generated, StandardCharsets.UTF_8);
            return;
        }
        assertTrue(Files.exists(DOC), "test-data/rule-capability/support-matrix.txt is missing; run with -DupdateMatrix=true");
        assertEquals(normalize(generated), normalize(Files.readString(DOC, StandardCharsets.UTF_8)),
                "test-data/rule-capability/support-matrix.txt is out of date; run with -DupdateMatrix=true and review the change");
    }
}
