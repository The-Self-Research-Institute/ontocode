package self.research.ontology.owlEditor.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IdentifierMentionLines {

    private static final Pattern XMLNS = Pattern.compile("xmlns:([\\w.\\-]+)\\s*=\\s*[\"']([^\"']*)[\"']");

    public record Result(Set<Long> lines, boolean capped) {}

    private IdentifierMentionLines() {
    }

    public static Result scan(BufferedReader reader, String identifier, boolean xml, int maxLines)
            throws IOException {
        SourceIdentifier target = new SourceIdentifier(identifier);
        Map<String, String> prefixes = new HashMap<>();
        Map<String, String> entities = new HashMap<>();
        Set<String> forms = forms(target, prefixes, entities);
        Set<Long> lines = new HashSet<>();
        if (target.isBlank()) {
            return new Result(lines, false);
        }
        String line;
        long lineNo = 0;
        while ((line = reader.readLine()) != null) {
            lineNo++;
            if (learn(line, xml, prefixes, entities)) {
                forms = forms(target, prefixes, entities);
            }
            if (mentions(line, forms)) {
                if (lines.size() >= maxLines) {
                    return new Result(lines, true);
                }
                lines.add(lineNo);
            }
        }
        return new Result(lines, false);
    }

    private static boolean learn(String line, boolean xml, Map<String, String> prefixes,
                                 Map<String, String> entities) {
        boolean changed = false;
        Matcher matcher = (xml ? XMLNS : TurtleSubjectBlockReader.PREFIX_DECLARATION).matcher(line);
        while (matcher.find()) {
            changed |= !matcher.group(2).equals(prefixes.put(matcher.group(1), matcher.group(2)));
        }
        if (xml && line.contains("<!ENTITY")) {
            Matcher entity = RdfXmlSubjectBlockReader.ENTITY.matcher(line);
            while (entity.find()) {
                String value = entity.group(2) != null ? entity.group(2) : entity.group(3);
                changed |= !value.equals(entities.put(entity.group(1), value));
            }
        }
        return changed;
    }

    private static Set<String> forms(SourceIdentifier target, Map<String, String> prefixes,
                                     Map<String, String> entities) {
        Set<String> forms = target.textForms(prefixes);
        String iri = target.resolve(prefixes);
        for (Map.Entry<String, String> entry : entities.entrySet()) {
            String namespace = entry.getValue();
            if (!namespace.isEmpty() && iri.startsWith(namespace) && iri.length() > namespace.length()) {
                forms.add("&" + entry.getKey() + ";" + iri.substring(namespace.length()));
            }
        }
        return forms;
    }

    private static boolean mentions(String line, Set<String> forms) {
        for (String form : forms) {
            if (SourceIdentifier.containsWithBoundaries(line, form)) {
                return true;
            }
        }
        return false;
    }
}
