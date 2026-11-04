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
    private static final Pattern ARITY = Pattern.compile("built-in ([\\w:]+) in rule \\S+:.*?invalid number of arguments - expecting (at least |at most )?(\\d+)(?: to (\\d+))? argument\\(s\\), got (\\d+)", Pattern.DOTALL);
    private static final Pattern RUNTIME = Pattern.compile("runtime exception thrown by built-in ([\\w:]+) in rule \\S+: ([\\w.$]+)(?:: ([^\\]\\n]*))?");
    private static final Pattern NOT_IMPLEMENTED = Pattern.compile("built-in ([\\w:]+) in rule \\S+: built-in not yet implemented");
    private static final Pattern UNBOUND_LATER = Pattern.compile("built-in ([\\w:]+) in rule .*?unbound variable \\?(\\w+) used in non-first position", Pattern.DOTALL);
    private static final Pattern UNBOUND_ARGUMENT = Pattern.compile("built-in ([\\w:]+) in rule .*?not expecting an unbound argument", Pattern.DOTALL);
    private static final Pattern BAD_REGEX = Pattern.compile("invalid regular expression '([^']*)'");
    private static final Pattern EXPECTING = Pattern.compile("expecting ([\\w:]+), got literal with value (\\S+) and type ([\\w:]+)");
    private static final Pattern ANTECEDENT_ONLY = Pattern.compile("built-in ([\\w:]+) in rule \\w+: built-in can only be used in antecedent");
    private static final Pattern SLICING_ARGUMENT = Pattern.compile("expecting xsd:int or xsd:integer argument for slicing operator ([\\w:]+)");
    private static final Pattern UNBOUND_RESULT = Pattern.compile("Variable \\?(\\w+) used in consequent is not present in antecedent");
    private static final Pattern PREFIX_MISSING = Pattern.compile("Prefix not registered for prefix name: ([A-Za-z][\\w-]*:)");
    private static final Pattern GENERIC_BUILT_IN = Pattern.compile("exception thrown by built-in ([\\w:]+) in rule \\S+: (?!exception thrown)([^\\]\\n]+)");
    private static final Pattern CONSEQUENCE = Pattern.compile("Exception executing consequence for rule \"[^\"]*\" in defaultpkg: (?:[\\w.$]+: )?(?:[\\w.$]+: )?([^\\]\\n]+)");
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
        String unknown = unknownName(raw);
        if (unknown != null && unknown.indexOf(':') > 0 && BuiltInCatalog.isLibraryPrefix(unknown.substring(0, unknown.indexOf(':')))) {
            return unknown + " is not a function the rule engine knows. The Built-ins tab lists every supported function.";
        }
        Matcher arity = ARITY.matcher(raw);
        if (arity.find()) {
            String quantity = (arity.group(2) != null ? arity.group(2) : "") + arity.group(3)
                    + (arity.group(4) != null ? " to " + arity.group(4) : "");
            return arity.group(1) + " needs " + quantity + " values (the variable that receives the result counts as the first one) but the rule gives it "
                    + arity.group(5) + ". Check the function list for the values it takes.";
        }
        Matcher notImplemented = NOT_IMPLEMENTED.matcher(raw);
        if (notImplemented.find()) {
            return notImplemented.group(1) + " is part of the SWRL standard but the rule engine does not implement it yet, so a rule cannot use it. "
                    + "Use a different function or compute the value another way.";
        }
        Matcher unboundLater = UNBOUND_LATER.matcher(raw);
        if (unboundLater.find()) {
            return "In " + unboundLater.group(1) + " only the first value may be a new variable, but ?" + unboundLater.group(2)
                    + " has no value yet. Put the condition that supplies it, for example hasAge(?p, ?" + unboundLater.group(2)
                    + "), before the function.";
        }
        Matcher unbound = UNBOUND_ARGUMENT.matcher(raw);
        if (unbound.find()) {
            return unbound.group(1) + " uses a variable that no earlier condition gives a value to. Put the condition that supplies "
                    + "the value, for example hasAge(?p, ?a), before the function.";
        }
        Matcher badRegex = BAD_REGEX.matcher(raw);
        if (badRegex.find()) {
            return "The pattern '" + badRegex.group(1) + "' is not a valid regular expression, so the text function cannot use it. Check the brackets and special characters.";
        }
        Matcher runtime = RUNTIME.matcher(raw);
        if (runtime.find()) {
            return runtime.group(1) + " could not work out a result for some of the data: " + runtimeReason(runtime.group(2), runtime.group(3))
                    + ". Check the values the rule reads, or use a different function.";
        }
        Matcher expecting = EXPECTING.matcher(raw);
        if (expecting.find()) {
            return "A value used by a function in the rule is " + typeName(expecting.group(3)) + " (" + expecting.group(2)
                    + ") but the function needs " + typeName(expecting.group(1))
                    + ". Check the data type of the property the rule reads, or use a different function.";
        }
        Matcher antecedentOnly = ANTECEDENT_ONLY.matcher(raw);
        if (antecedentOnly.find()) {
            return antecedentOnly.group(1) + " belongs in the condition part of the query (before the arrow), not in the result part after it.";
        }
        if (raw.contains("slicing operator used without an order clause")) {
            return "This operator picks rows by their position, so the query needs a sort order. Add sqwrl:orderBy(?x) or sqwrl:orderByDescending(?x) after sqwrl:select.";
        }
        Matcher slicing = SLICING_ARGUMENT.matcher(raw);
        if (slicing.find()) {
            return slicing.group(1) + " takes only a whole number, for example " + slicing.group(1) + "(3), not a variable.";
        }
        Matcher unboundResult = UNBOUND_RESULT.matcher(raw);
        if (unboundResult.find()) {
            return "The variable ?" + unboundResult.group(1) + " is used after the arrow but nothing before the arrow gives it a value. "
                    + "Add a condition before the arrow that sets it, for example hasAge(?p, ?" + unboundResult.group(1)
                    + "). In a query, collection functions such as sqwrl:makeSet and sqwrl:size also go before the arrow.";
        }
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
        Matcher prefix = PREFIX_MISSING.matcher(raw);
        if (prefix.find()) {
            return "The rule uses the prefix " + prefix.group(1) + " which is not defined in this project. The function prefixes are "
                    + BuiltInCatalog.registered().keySet().stream().map(p -> p + ":").collect(java.util.stream.Collectors.joining(", "))
                    + ". If it is a project prefix, reload the project and run it again.";
        }
        if (raw.contains("Prefix not registered")) {
            return "A name in the ontology could not be resolved. Reload the project and run it again.";
        }
        Matcher generic = GENERIC_BUILT_IN.matcher(raw);
        if (generic.find()) {
            return generic.group(1) + " could not run: " + generic.group(2).trim()
                    + ". Check the values the rule uses, or use a different function.";
        }
        Matcher consequence = CONSEQUENCE.matcher(raw);
        if (consequence.find()) {
            return "The conclusion of the rule could not be applied: " + consequence.group(1).trim()
                    + ". Check the values the conclusion uses.";
        }
        return null;
    }

    private static String runtimeReason(String exception, String detail) {
        String text = detail == null ? "" : detail.trim();
        if (text.contains("/ by zero") || text.contains("Division by zero") || text.contains("Division undefined")) {
            return "it divides by zero";
        }
        if (text.contains("Rounding necessary")) {
            return "the engine cannot round this kind of number";
        }
        if (text.contains("does not match pattern")) {
            return "a date, time or duration is not in the form the function expects";
        }
        String shortName = exception.substring(exception.lastIndexOf('.') + 1);
        return text.isEmpty() ? shortName : shortName + " (" + text + ")";
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
