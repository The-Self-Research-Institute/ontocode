package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.semanticweb.owlapi.model.SWRLRule;
import self.research.ontology.owlEditor.service.spreadsheet.RuleImportFile;
import self.research.ontology.owlEditor.service.spreadsheet.RuleSpreadsheet;
import self.research.ontology.owlEditor.util.OwlAxiomSparqlWriterRuleTest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class RuleImportSupport {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final List<SWRLRule> DEMO = new OwlAxiomSparqlWriterRuleTest().demoRules();
    static final AtomicInteger PARSES = new AtomicInteger();
    static volatile Consumer<String> parseHook = text -> {
    };
    static volatile java.util.function.Supplier<List<Map<String, Object>>> embedded = List::of;

    private RuleImportSupport() {
    }

    static SwrlRuleClient client() {
        return new SwrlRuleClient((method, url, body, token) -> {
            if (url.contains("/rules/embedded")) {
                return new SwrlRuleClient.Answer(200, MAPPER.writeValueAsString(embedded.get()));
            }
            if (!url.endsWith("/rules/parse")) {
                return new SwrlRuleClient.Answer(200, "[]");
            }
            PARSES.incrementAndGet();
            JsonNode request = MAPPER.readTree(body);
            String text = request.path("ruleText").asText();
            parseHook.accept(text);
            return new SwrlRuleClient.Answer(200, MAPPER.writeValueAsString(answer(text)));
        }, "http://swrl", "t");
    }

    static Map<String, Object> answer(String text) throws Exception {
        Map<String, Object> answer = new HashMap<>();
        answer.put("errors", List.of());
        answer.put("suggestions", List.of());
        if (text.startsWith("demo ")) {
            SWRLRule rule = DEMO.get(Integer.parseInt(text.substring(5).trim()) - 1).getAxiomWithoutAnnotations();
            answer.put("valid", true);
            answer.put("axiom", RuleTestSupport.functional(rule));
            return answer;
        }
        if (text.startsWith("bad:")) {
            answer.put("valid", false);
            answer.put("axiom", null);
            answer.put("errors", List.of("Unknown class or property '" + text.substring(4) + "'"));
            answer.put("suggestions", List.of("Did you mean: Student, Parent?", "'" + text.substring(4) + "' does not exist in this ontology."));
            return answer;
        }
        if (text.startsWith("garbage")) {
            answer.put("valid", false);
            answer.put("axiom", null);
            answer.put("errors", List.of("The rule could not be understood"));
            return answer;
        }
        String plain = text.replace("(?x)", "");
        String[] parts = plain.contains("->") ? plain.split("->") : plain.split(">");
        answer.put("valid", true);
        answer.put("axiom", RuleTestSupport.functionalRule(local(parts[0]), local(parts[1])));
        return answer;
    }

    private static String local(String token) {
        String value = token.trim();
        if (value.startsWith("<") && value.endsWith(">")) {
            value = value.substring(1, value.length() - 1);
            return value.startsWith(RuleTestSupport.NS) ? value.substring(RuleTestSupport.NS.length()) : value;
        }
        return value;
    }

    static RuleImportFile.Row row(int number, String name, String text) {
        return new RuleImportFile.Row("Rules", number, name, text, "", "", true, null, null);
    }

    static RuleImportFile.Row row(int number, String name, String text, boolean enabled) {
        return new RuleImportFile.Row("Rules", number, name, text, "", "", enabled, null, null);
    }

    static RuleImportFile file(String fileName, RuleImportFile.Row... rows) {
        return file(fileName, Map.of(), rows);
    }

    static RuleImportFile file(String fileName, Map<String, String> prefixes, RuleImportFile.Row... rows) {
        StringBuilder text = new StringBuilder(fileName);
        for (RuleImportFile.Row row : rows) {
            text.append('|').append(row.name()).append('|').append(row.text()).append('|').append(row.enabled());
        }
        return new RuleImportFile(fileName, RuleSpreadsheet.sha256(text.toString().getBytes(StandardCharsets.UTF_8)),
                List.of(rows), prefixes);
    }

    static RuleImportFile demoFile(String fileName) {
        List<RuleImportFile.Row> rows = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            rows.add(row(i + 1, "Demo " + i, "demo " + i, i != 12));
        }
        return file(fileName, rows.toArray(new RuleImportFile.Row[0]));
    }
}
