package self.research.ontology.owlEditor.service;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SavedRulesFixtures {

    public static final String NS = "http://example.org/sr#";

    private SavedRulesFixtures() {
    }

    public static String rulesText(String ruleName) {
        return "Prefix(:=<" + NS + ">)\n"
                + "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n"
                + "Ontology(\n"
                + "Declaration(Class(:Person))\n"
                + "Declaration(Class(:Adult))\n"
                + "DLSafeRule(Annotation(rdfs:label \"" + ruleName + "\") "
                + "Body(ClassAtom(:Person Variable(<urn:swrl:var#x>))) "
                + "Head(ClassAtom(:Adult Variable(<urn:swrl:var#x>))))\n"
                + ")\n";
    }

    public static SavedRulesService.HttpAnswer ok(String body, String fingerprint, String skippedJson) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (fingerprint != null) {
            headers.put("X-Ontocode-Rules-Fingerprint", fingerprint);
        }
        if (skippedJson != null) {
            headers.put("X-Ontocode-Skipped-Rules", skippedJson);
        }
        return new SavedRulesService.HttpAnswer(200, headers, body);
    }

    public static SavedRulesService withRule() {
        return new SavedRulesService((url, token) -> ok(rulesText("adult"), "fp-1", null), "http://swrl", "t");
    }

    public static SavedRulesService withoutRules() {
        return new SavedRulesService((url, token) -> ok("", "none", null), "http://swrl", "t");
    }

    public static SavedRulesService unreachable() {
        return new SavedRulesService((url, token) -> {
            throw new java.io.IOException("connection refused");
        }, "http://swrl", "t");
    }
}
