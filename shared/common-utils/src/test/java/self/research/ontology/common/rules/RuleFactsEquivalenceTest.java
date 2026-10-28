package self.research.ontology.common.rules;

import org.junit.jupiter.api.Test;
import org.semanticweb.HermiT.ReasonerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleFactsEquivalenceTest {

    static List<String> summary(RuleExecutionResult r) {
        List<String> out = new ArrayList<>();
        out.add("status " + r.status());
        out.add("rounds " + r.stopping().rounds() + " converged " + r.stopping().converged() + " limit " + r.stopping().limit());
        out.add("executed " + r.executedRuleIds().stream().sorted().collect(Collectors.toList()));
        out.add("skipped " + r.skippedRules().stream().map(s -> s.ruleId() + " " + s.reason()).sorted().collect(Collectors.toList()));
        out.add("limitations " + r.limitations());
        r.derived().forEach(d -> out.add(d.origin() + " " + d.round() + " " + RuleExecution.canonical(d.axiom())));
        return out;
    }

    static void assertSame(String text, String label) throws Exception {
        RuleExecutionResult fresh = RuleExecutionTest.execution(new MiniRuleEngine()).run(RuleExecutionTest.load(text), "s", RuleExecutionLimits.defaults());
        LegacyRuleExecution legacy = new LegacyRuleExecution(new MiniRuleEngine(), o -> new ReasonerFactory().createReasoner(o), "HermiT 1.4.5.519");
        RuleExecutionResult old = legacy.run(RuleExecutionTest.load(text), "s", RuleExecutionLimits.defaults());
        assertEquals(summary(old), summary(fresh), label);
    }

    @Test
    void everyCapabilityFixtureDerivesTheSameFactsAsTheOldImplementation() throws Exception {
        List<Path> files;
        try (Stream<Path> s = Files.list(RuleExecutionTest.DIR)) {
            files = s.filter(p -> p.toString().endsWith(".ofn")).sorted().collect(Collectors.toList());
        }
        assertTrue(files.size() >= 16);
        for (Path p : files) {
            assertSame(Files.readString(p, StandardCharsets.UTF_8), p.getFileName().toString());
        }
    }

    @Test
    void generatedOntologiesDeriveTheSameFactsAsTheOldImplementation() throws Exception {
        for (int n : new int[]{100, 300}) {
            assertSame(RuleFactsGenerator.generate(n, RuleFactsGenerator.SINGLE_RULE), "single rule n=" + n);
            assertSame(RuleFactsGenerator.generate(n, RuleFactsGenerator.DEMO_RULES), "demo rules n=" + n);
        }
    }

    private static final String AGENT_RULE_ON = "DLSafeRule(Body(%s) Head(ClassAtom(:Agent Variable(:x))))\n";

    @Test
    void trickyAxiomShapesDeriveTheSameFactsAsTheOldImplementation() throws Exception {
        String head = "Prefix(:=<http://example.org/t#>)\nPrefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)\n"
                + "Prefix(swrlb:=<http://www.w3.org/2003/11/swrlb#>)\nOntology(<http://example.org/t>\n";
        String decl = "Declaration(Class(:Person))\nDeclaration(Class(:Adult))\nDeclaration(Class(:Agent))\nDeclaration(Class(:Student))\n"
                + "Declaration(Class(:Teacher))\nDeclaration(Class(:Londoner))\n"
                + "Declaration(ObjectProperty(:hasParent))\nDeclaration(ObjectProperty(:hasChild))\n"
                + "Declaration(ObjectProperty(:hasGrandparent))\nDeclaration(ObjectProperty(:livesIn))\n"
                + "Declaration(DataProperty(:hasAge))\nDeclaration(DataProperty(:hasPrice))\nDeclaration(DataProperty(:hasTotal))\n"
                + "Declaration(NamedIndividual(:a))\nDeclaration(NamedIndividual(:b))\nDeclaration(NamedIndividual(:c))\n"
                + "Declaration(NamedIndividual(:London))\n";
        String[] bodies = {
            "EquivalentClasses(:Adult ObjectIntersectionOf(:Person ObjectHasValue(:livesIn :London)))\nClassAssertion(:Person :a)\n"
                + "ObjectPropertyAssertion(:livesIn :a :London)\n" + AGENT_RULE_ON.formatted("ClassAtom(:Adult Variable(:x))"),
            "InverseObjectProperties(:hasParent :hasChild)\nObjectPropertyAssertion(:hasChild :a :b)\n"
                + AGENT_RULE_ON.formatted("ObjectPropertyAtom(:hasParent Variable(:x) Variable(:y))"),
            "TransitiveObjectProperty(:hasParent)\nObjectPropertyAssertion(:hasParent :a :b)\nObjectPropertyAssertion(:hasParent :b :c)\n"
                + AGENT_RULE_ON.formatted("ObjectPropertyAtom(:hasParent Variable(:x) :c)"),
            "SubObjectPropertyOf(ObjectPropertyChain(:hasParent :hasParent) :hasGrandparent)\nObjectPropertyAssertion(:hasParent :a :b)\n"
                + "ObjectPropertyAssertion(:hasParent :b :c)\n"
                + AGENT_RULE_ON.formatted("ObjectPropertyAtom(:hasGrandparent Variable(:x) Variable(:y))"),
            "SubObjectPropertyOf(:hasChild :hasParent)\nObjectPropertyAssertion(:hasChild :a :b)\n"
                + AGENT_RULE_ON.formatted("ObjectPropertyAtom(:hasParent Variable(:x) Variable(:y))"),
            "SameIndividual(:a :b)\nClassAssertion(:Person :a)\nDataPropertyAssertion(:hasAge :b \"20\"^^xsd:integer)\n"
                + "DLSafeRule(Body(ClassAtom(:Person Variable(:x)) DataPropertyAtom(:hasAge Variable(:x) Variable(:n))) Head(ClassAtom(:Adult Variable(:x))))\n",
            "SubClassOf(:Student ObjectHasValue(:livesIn :London))\nClassAssertion(:Student :a)\n"
                + "DLSafeRule(Body(ObjectPropertyAtom(:livesIn Variable(:x) :London)) Head(ClassAtom(:Londoner Variable(:x))))\n",
            "SubDataPropertyOf(:hasPrice :hasTotal)\nDataPropertyAssertion(:hasPrice :a \"5\"^^xsd:integer)\n"
                + AGENT_RULE_ON.formatted("DataPropertyAtom(:hasTotal Variable(:x) Variable(:v))"),
            "ObjectPropertyDomain(:livesIn :Londoner)\nObjectPropertyAssertion(:livesIn :a :London)\n"
                + AGENT_RULE_ON.formatted("ClassAtom(:Londoner Variable(:x))"),
            "DisjointClasses(:Student :Teacher)\nClassAssertion(:Student :a)\n"
                + "DLSafeRule(Body(ClassAtom(:Student Variable(:x))) Head(ClassAtom(:Person Variable(:x))))\n"
        };
        for (int i = 0; i < bodies.length; i++) {
            assertSame(head + decl + bodies[i] + ")\n", "tricky shape " + i);
        }
    }

    private static String ruleC(String c) {
        return "DLSafeRule(Body(ClassAtom(:" + c + " Variable(:x)) ObjectPropertyAtom(:likes Variable(:x) Variable(:y)) "
                + "DataPropertyAtom(:hasAge Variable(:y) Variable(:n)) BuiltInAtom(swrlb:greaterThan Variable(:n) \"17\"^^xsd:integer)) "
                + "Head(ClassAtom(:Agent Variable(:x))))\n";
    }

    private static String ruleP(String p) {
        return "DLSafeRule(Body(ObjectPropertyAtom(:" + p + " Variable(:x) Variable(:y)) "
                + "DataPropertyAtom(:hasAge Variable(:y) Variable(:n)) BuiltInAtom(swrlb:greaterThan Variable(:n) \"17\"^^xsd:integer)) "
                + "Head(ClassAtom(:Agent Variable(:x))))\n";
    }

    private static String ruleD(String d) {
        return "DLSafeRule(Body(DataPropertyAtom(:" + d + " Variable(:x) Variable(:v)) ObjectPropertyAtom(:likes Variable(:x) Variable(:y)) "
                + "DataPropertyAtom(:hasAge Variable(:y) Variable(:n)) BuiltInAtom(swrlb:greaterThan Variable(:n) \"17\"^^xsd:integer)) "
                + "Head(ClassAtom(:Agent Variable(:x))))\n";
    }

    static final String RISK_HEAD = "Prefix(:=<http://example.org/t#>)\nPrefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)\n"
            + "Prefix(swrlb:=<http://www.w3.org/2003/11/swrlb#>)\nOntology(<http://example.org/t>\n"
            + "Declaration(Class(:Person))\nDeclaration(Class(:Adult))\nDeclaration(Class(:Agent))\nDeclaration(Class(:Student))\n"
            + "Declaration(Class(:Teacher))\nDeclaration(Class(:Londoner))\n"
            + "Declaration(ObjectProperty(:hasParent))\nDeclaration(ObjectProperty(:hasChild))\nDeclaration(ObjectProperty(:hasGrandparent))\n"
            + "Declaration(ObjectProperty(:livesIn))\nDeclaration(ObjectProperty(:knows))\nDeclaration(ObjectProperty(:likes))\n"
            + "Declaration(DataProperty(:hasAge))\nDeclaration(DataProperty(:hasPrice))\nDeclaration(DataProperty(:hasTotal))\n"
            + "Declaration(DataProperty(:hasName))\n"
            + "Declaration(NamedIndividual(:a))\nDeclaration(NamedIndividual(:b))\nDeclaration(NamedIndividual(:c))\nDeclaration(NamedIndividual(:London))\n"
            + "ObjectPropertyAssertion(:likes :a :c)\nObjectPropertyAssertion(:likes :b :c)\nObjectPropertyAssertion(:likes :c :c)\n"
            + "DataPropertyAssertion(:hasAge :a \"30\"^^xsd:integer)\nDataPropertyAssertion(:hasAge :b \"30\"^^xsd:integer)\n"
            + "DataPropertyAssertion(:hasAge :c \"30\"^^xsd:integer)\n";

    @Test
    void everyKindOfSkippedOrKeptClassAndPropertyDerivesTheSameFactsAsTheOldImplementation() throws Exception {
        String[] bodies = {
            "ObjectPropertyAssertion(ObjectInverseOf(:hasParent) :a :b)\n" + ruleP("hasParent"),
            "SymmetricObjectProperty(:knows)\nObjectPropertyAssertion(:knows :a :c)\n" + ruleP("knows"),
            "InverseObjectProperties(:hasParent :hasChild)\nObjectPropertyAssertion(:hasChild :a :c)\n" + ruleP("hasParent"),
            "TransitiveObjectProperty(:knows)\nObjectPropertyAssertion(:knows :a :b)\nObjectPropertyAssertion(:knows :b :c)\n" + ruleP("knows"),
            "SubObjectPropertyOf(ObjectPropertyChain(:hasParent :hasParent) :hasGrandparent)\nObjectPropertyAssertion(:hasParent :a :b)\n"
                + "ObjectPropertyAssertion(:hasParent :b :c)\n" + ruleP("hasGrandparent"),
            "SubObjectPropertyOf(:hasChild :hasParent)\nObjectPropertyAssertion(:hasChild :a :c)\n" + ruleP("hasParent"),
            "EquivalentClasses(:Adult ObjectIntersectionOf(:Person ObjectHasValue(:livesIn :London)))\nClassAssertion(:Person :a)\n"
                + "ObjectPropertyAssertion(:livesIn :a :London)\n" + ruleC("Adult"),
            "SameIndividual(:a :b)\nClassAssertion(:Person :a)\n" + ruleC("Person"),
            "SameIndividual(:a :b)\nObjectPropertyAssertion(:knows :a :c)\n" + ruleP("knows"),
            "FunctionalObjectProperty(:hasParent)\nObjectPropertyAssertion(:hasParent :a :b)\nObjectPropertyAssertion(:hasParent :a :c)\n"
                + "ClassAssertion(:Person :b)\n" + ruleC("Person") + ruleP("hasParent"),
            "InverseFunctionalObjectProperty(:hasChild)\nObjectPropertyAssertion(:hasChild :a :c)\nObjectPropertyAssertion(:hasChild :b :c)\n"
                + "ClassAssertion(:Person :a)\n" + ruleC("Person"),
            "HasKey(:Person () (:hasName))\nClassAssertion(:Person :a)\nClassAssertion(:Person :b)\n"
                + "DataPropertyAssertion(:hasName :a \"n\")\nDataPropertyAssertion(:hasName :b \"n\")\n"
                + "ObjectPropertyAssertion(:knows :a :c)\n" + ruleP("knows"),
            "EquivalentClasses(:Londoner ObjectOneOf(:a :b))\n" + ruleC("Londoner"),
            "SubClassOf(:Person ObjectMaxCardinality(1 :hasParent))\nClassAssertion(:Person :a)\nObjectPropertyAssertion(:hasParent :a :b)\n"
                + "ObjectPropertyAssertion(:hasParent :a :c)\n" + ruleP("hasParent"),
            "SubDataPropertyOf(:hasPrice :hasTotal)\nDataPropertyAssertion(:hasPrice :a \"5\"^^xsd:integer)\n" + ruleD("hasTotal"),
            "DataPropertyAssertion(:hasPrice :a \"05\"^^xsd:integer)\nDataPropertyAssertion(:hasPrice :b \"1.50\"^^xsd:decimal)\n"
                + "DataPropertyAssertion(:hasPrice :c \"x\"@en)\n" + ruleD("hasPrice"),
            "ClassAssertion(:Student :a)\nSubClassOf(ObjectComplementOf(:Student) :Londoner)\n" + ruleC("Londoner") + ruleC("Student"),
            "ClassAssertion(ObjectSomeValuesFrom(:livesIn :London) :a)\nSubClassOf(ObjectSomeValuesFrom(:livesIn :London) :Londoner)\n" + ruleC("Londoner"),
            "NegativeObjectPropertyAssertion(:hasParent :a :b)\nObjectPropertyAssertion(:hasParent :a :c)\n" + ruleP("hasParent"),
            "ReflexiveObjectProperty(:knows)\n" + ruleP("knows"),
            "ObjectPropertyDomain(:likes :Londoner)\n" + ruleC("Londoner"),
            "ObjectPropertyRange(:hasParent :Person)\nObjectPropertyAssertion(:hasParent :a :b)\n" + ruleC("Person"),
            "DataPropertyDomain(:hasPrice :Person)\nDataPropertyAssertion(:hasPrice :a \"5\"^^xsd:integer)\n" + ruleC("Person"),
            "DisjointClasses(:Student :Teacher)\nClassAssertion(:Student :a)\nClassAssertion(:Teacher :a)\n" + ruleC("Student"),
            "DLSafeRule(Body(ObjectPropertyAtom(:knows Variable(:x) Variable(:y))) Head(SameIndividualAtom(Variable(:x) Variable(:y))))\n"
                + "ObjectPropertyAssertion(:knows :a :b)\nClassAssertion(:Person :a)\n" + ruleC("Person"),
            "SubClassOf(:Person ObjectHasValue(:knows :c))\nClassAssertion(:Person :a)\n" + ruleP("knows"),
            "SubClassOf(:Person ObjectAllValuesFrom(:knows :Londoner))\nClassAssertion(:Person :a)\nObjectPropertyAssertion(:knows :a :b)\n" + ruleC("Londoner"),
            "SubClassOf(ObjectSomeValuesFrom(:likes :Person) :Londoner)\nClassAssertion(:Person :c)\n" + ruleC("Londoner"),
            "ClassAssertion(:Person _:x)\nObjectPropertyAssertion(:knows :a _:x)\nObjectPropertyAssertion(:knows :a :b)\n" + ruleP("knows"),
            "DLSafeRule(Body(ClassAtom(ObjectAllValuesFrom(:likes :Person) Variable(:x)) ObjectPropertyAtom(:likes Variable(:x) Variable(:y)) "
                + "DataPropertyAtom(:hasAge Variable(:y) Variable(:n)) BuiltInAtom(swrlb:greaterThan Variable(:n) \"17\"^^xsd:integer)) "
                + "Head(ClassAtom(:Agent Variable(:x))))\nClassAssertion(:Person :c)\n",
            "DifferentIndividuals(:a :b)\nObjectPropertyAssertion(:knows :a :c)\nObjectPropertyAssertion(:knows :b :c)\n" + ruleP("knows")
        };
        List<String> mismatches = new ArrayList<>();
        for (int i = 0; i < bodies.length; i++) {
            try {
                assertSame(RISK_HEAD + bodies[i] + ")\n", "risk shape " + i);
            } catch (AssertionError e) {
                mismatches.add("risk shape " + i + ": " + e.getMessage());
            }
        }
        assertTrue(mismatches.isEmpty(), String.join("\n", mismatches));
    }
}
