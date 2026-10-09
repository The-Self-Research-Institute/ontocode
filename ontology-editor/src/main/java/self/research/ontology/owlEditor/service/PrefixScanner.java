package self.research.ontology.owlEditor.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PrefixScanner {

    private static final int MAX_LINES = 5_000;

    private static final Pattern TURTLE = Pattern.compile(
            "^\\s*(?:@prefix|PREFIX)\\s+([^\\s:]*):\\s*<([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML = Pattern.compile("xmlns(?::([\\w.-]+))?\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern FUNCTIONAL = Pattern.compile("^\\s*Prefix\\(\\s*([^:=\\s]*):?\\s*=\\s*<([^>]*)>\\s*\\)");
    private static final Pattern MANCHESTER = Pattern.compile("^\\s*Prefix:\\s*([^:\\s]*):\\s*<([^>]*)>");

    private PrefixScanner() {
    }

    static Map<String, String> scan(Path file, String format) throws IOException {
        String kind = format == null ? "" : format.toLowerCase(Locale.ROOT);
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        return switch (kind) {
            case "turtle", "ttl" -> scanLines(file, TURTLE);
            case "functional", "functionalsyntax" -> scanLines(file, FUNCTIONAL);
            case "manchester", "manchestersyntax" -> scanLines(file, MANCHESTER);
            case "rdfxml", "rdf", "owl", "xml", "owlxml" -> scanRootTag(file);
            default -> new LinkedHashMap<>();
        };
    }

    private static Map<String, String> scanLines(Path file, Pattern pattern) throws IOException {
        Map<String, String> prefixes = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count++ < MAX_LINES) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    prefixes.putIfAbsent(matcher.group(1), matcher.group(2));
                }
            }
        }
        return prefixes;
    }

    private static Map<String, String> scanRootTag(Path file) throws IOException {
        Map<String, String> prefixes = new LinkedHashMap<>();
        boolean inRootTag = false;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count++ < MAX_LINES) {
                if (!inRootTag && (line.contains("<rdf:RDF") || line.contains("<Ontology") || line.contains("<RDF"))) {
                    inRootTag = true;
                }
                if (!inRootTag) {
                    continue;
                }
                Matcher matcher = XML.matcher(line);
                while (matcher.find()) {
                    prefixes.putIfAbsent(matcher.group(1) == null ? "" : matcher.group(1), matcher.group(2));
                }
                if (line.contains(">")) {
                    break;
                }
            }
        }
        return prefixes;
    }
}
