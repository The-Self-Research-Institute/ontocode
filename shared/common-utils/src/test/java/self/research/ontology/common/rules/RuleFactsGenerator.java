package self.research.ontology.common.rules;

final class RuleFactsGenerator {

    static final String SINGLE_RULE = "DLSafeRule(Body(ClassAtom(:Person Variable(:x)) DataPropertyAtom(:hasAge Variable(:x) Variable(:a)) "
            + "BuiltInAtom(swrlb:greaterThan Variable(:a) \"17\"^^xsd:integer)) Head(ClassAtom(:Adult Variable(:x))))\n";

    static final String DEMO_RULES = String.join("",
            "DLSafeRule(Body(ClassAtom(:Person Variable(:x))) Head(ClassAtom(:Agent Variable(:x))))\n",
            "DLSafeRule(Body(ObjectPropertyAtom(:hasParent Variable(:x) Variable(:y)) ObjectPropertyAtom(:hasParent Variable(:y) Variable(:z))) Head(ObjectPropertyAtom(:hasGrandparent Variable(:x) Variable(:z))))\n",
            SINGLE_RULE,
            "DLSafeRule(Body(DataPropertyAtom(:hasPrice Variable(:x) Variable(:p)) DataPropertyAtom(:hasTax Variable(:x) Variable(:t)) BuiltInAtom(swrlb:add Variable(:total) Variable(:p) Variable(:t))) Head(DataPropertyAtom(:hasTotal Variable(:x) Variable(:total))))\n",
            "DLSafeRule(Body(ObjectPropertyAtom(:hasParent Variable(:x) Variable(:a)) ObjectPropertyAtom(:hasParent Variable(:x) Variable(:b)) DifferentIndividualsAtom(Variable(:a) Variable(:b))) Head(ClassAtom(:HasTwoParents Variable(:x))))\n",
            "DLSafeRule(Body(ClassAtom(:Person Variable(:x)) ObjectPropertyAtom(:livesIn Variable(:x) :London)) Head(ClassAtom(:Londoner Variable(:x))))\n",
            "DLSafeRule(Body(DataPropertyAtom(:hasStatus Variable(:x) \"active\")) Head(ClassAtom(:Active Variable(:x))))\n",
            "DLSafeRule(Body(ClassAtom(ObjectSomeValuesFrom(:hasChild :Person) Variable(:x))) Head(ClassAtom(:Parent Variable(:x)) ClassAtom(:Agent Variable(:x))))\n",
            "DLSafeRule(Body(DataPropertyAtom(:hasAge Variable(:x) Variable(:a)) DataRangeAtom(xsd:integer Variable(:a))) Head(ClassAtom(:HasIntAge Variable(:x))))\n",
            "DLSafeRule(Body(ClassAtom(:Student Variable(:x))) Head(ClassAtom(:Person Variable(:x))))\n",
            "DLSafeRule(Body(ClassAtom(:Teacher Variable(:x))) Head(ClassAtom(:Person Variable(:x))))\n");

    static String generate(int n, String rules) {
        StringBuilder b = new StringBuilder();
        b.append("Prefix(:=<http://example.org/gen#>)\nPrefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)\n")
                .append("Prefix(swrlb:=<http://www.w3.org/2003/11/swrlb#>)\nOntology(<http://example.org/gen>\n");
        for (String c : new String[]{"Person", "Student", "Teacher", "Adult", "Agent", "Parent", "Londoner", "Active", "HasTwoParents", "HasIntAge"}) {
            b.append("Declaration(Class(:").append(c).append("))\n");
        }
        for (String p : new String[]{"hasParent", "hasGrandparent", "hasChild", "livesIn"}) {
            b.append("Declaration(ObjectProperty(:").append(p).append("))\n");
        }
        for (String p : new String[]{"hasAge", "hasPrice", "hasTax", "hasTotal", "hasStatus"}) {
            b.append("Declaration(DataProperty(:").append(p).append("))\n");
        }
        b.append("Declaration(NamedIndividual(:London))\n");
        for (int i = 0; i < n; i++) {
            b.append("Declaration(NamedIndividual(:p").append(i).append("))\n");
            b.append("ClassAssertion(:").append(i % 7 == 0 ? "Student" : "Person").append(" :p").append(i).append(")\n");
            b.append("DataPropertyAssertion(:hasAge :p").append(i).append(" \"").append(i % 60).append("\"^^xsd:integer)\n");
            b.append("ObjectPropertyAssertion(:hasParent :p").append(i).append(" :p").append((i + 1) % n).append(")\n");
            if (i % 3 == 0) {
                b.append("ObjectPropertyAssertion(:hasParent :p").append(i).append(" :p").append((i + 2) % n).append(")\n");
            }
            if (i % 5 == 0) {
                b.append("ObjectPropertyAssertion(:livesIn :p").append(i).append(" :London)\n");
            }
            if (i % 4 == 0) {
                b.append("ObjectPropertyAssertion(:hasChild :p").append(i).append(" :p").append((i + 3) % n).append(")\n");
            }
            if (i % 10 == 0) {
                b.append("DataPropertyAssertion(:hasPrice :p").append(i).append(" \"").append(i).append("\"^^xsd:integer)\n");
                b.append("DataPropertyAssertion(:hasTax :p").append(i).append(" \"2\"^^xsd:integer)\n");
                b.append("DataPropertyAssertion(:hasStatus :p").append(i).append(" \"active\")\n");
            }
        }
        b.append(rules).append(")\n");
        return b.toString();
    }

    private RuleFactsGenerator() {
    }
}
