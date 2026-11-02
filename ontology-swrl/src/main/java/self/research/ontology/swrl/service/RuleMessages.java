package self.research.ontology.swrl.service;

import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RuleMessages {

    private static final Pattern CONVERT = Pattern.compile("cannot convert value (?:(\\S+) )?of type ([\\w:]+) to ([\\w:]+)");
    private static final Pattern UNKNOWN_NAME = Pattern.compile("Invalid SWRL atom predicate '([^']+)'");
    private static final int DETAIL_LIMIT = 300;

    private RuleMessages() {
    }

    static String explain(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String plain = plainSentence(raw);
        if (plain == null) {
            return raw;
        }
        String detail = raw.replace('\n', ' ').trim();
        if (detail.length() > DETAIL_LIMIT) {
            detail = detail.substring(0, DETAIL_LIMIT) + "...";
        }
        return plain + " (Technical detail: " + detail + ")";
    }

    static String plainSentence(String raw) {
        Matcher convert = CONVERT.matcher(raw);
        if (convert.find()) {
            String value = convert.group(1);
            return "A value used by a function in the rule is " + typeName(convert.group(2))
                    + (value != null ? " (" + value + ")" : "") + " but the function needs " + typeName(convert.group(3))
                    + ". Check the data type of the property the rule reads, or use a different function.";
        }
        if (raw.contains("unsupported argument type")) {
            return "A comparison or text function received a value it cannot use. These functions work on numbers, text, "
                    + "dates, times and true/false values. Check the data type of the property the rule reads.";
        }
        if (raw.contains("do not support argument binding")) {
            return "A function in the rule uses a variable that no earlier condition gives a value to. Put the condition "
                    + "that supplies the value, for example hasAge(?p, ?a), before the function.";
        }
        if (raw.contains("Prefix not registered")) {
            return "A name in the ontology could not be resolved. Reload the project and run it again.";
        }
        return null;
    }

    static String typeName(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (t.endsWith("integer") || t.endsWith("int") || t.endsWith("long") || t.endsWith("short") || t.endsWith("byte")) {
            return "a whole number";
        }
        if (t.endsWith("decimal")) {
            return "a decimal number";
        }
        if (t.endsWith("double") || t.endsWith("float")) {
            return "a number";
        }
        if (t.endsWith("string") || t.endsWith("plainliteral")) {
            return "text";
        }
        if (t.endsWith("boolean")) {
            return "a true/false value";
        }
        if (t.endsWith("date") || t.endsWith("datetime") || t.endsWith("time") || t.endsWith("duration")) {
            return "a date or time";
        }
        return "a value of type " + type;
    }

    static String unknownName(String raw) {
        Matcher m = UNKNOWN_NAME.matcher(raw == null ? "" : raw);
        return m.find() ? m.group(1) : null;
    }

    static Set<String> namesIn(OWLOntology ontology) {
        Set<String> names = new LinkedHashSet<>();
        for (OWLEntity entity : ontology.getClassesInSignature()) {
            names.add(entity.getIRI().getShortForm());
        }
        for (OWLEntity entity : ontology.getObjectPropertiesInSignature()) {
            names.add(entity.getIRI().getShortForm());
        }
        for (OWLEntity entity : ontology.getDataPropertiesInSignature()) {
            names.add(entity.getIRI().getShortForm());
        }
        return names;
    }

    static List<String> closestNames(String wanted, Collection<String> available, int max) {
        String w = wanted.toLowerCase(Locale.ROOT);
        int tolerance = Math.max(2, w.length() / 3);
        List<String> close = new ArrayList<>();
        for (String name : available) {
            String n = name.toLowerCase(Locale.ROOT);
            if (name.equals(wanted)) {
                continue;
            }
            boolean contains = w.length() >= 4 && (n.contains(w) || w.contains(n)) && n.length() >= 4;
            if (contains || distance(w, n) <= tolerance) {
                close.add(name);
            }
        }
        close.sort(Comparator.comparingInt((String n) -> distance(w, n.toLowerCase(Locale.ROOT))).thenComparing(Comparator.naturalOrder()));
        return close.size() > max ? new ArrayList<>(close.subList(0, max)) : close;
    }

    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    static String normalizeTextLiterals(String ruleText) {
        if (ruleText == null || ruleText.indexOf('"') < 0) {
            return ruleText;
        }
        return ruleText.replaceAll("\"((?:[^\"\\\\]|\\\\.)*)\"(?!\\^\\^|@)", "\"$1\"^^xsd:string");
    }
}
