package self.research.ontology.owlEditor.util;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class SourceIdentifier {

    private final String raw;

    public SourceIdentifier(String value) {
        this.raw = value == null ? "" : value.trim();
    }

    public String raw() {
        return raw;
    }

    public boolean isBlank() {
        return raw.isEmpty();
    }

    public String resolve(Map<String, String> prefixes) {
        if (raw.startsWith("<") && raw.endsWith(">") && raw.length() >= 2) {
            return raw.substring(1, raw.length() - 1);
        }
        String expanded = expandPrefixed(raw, prefixes);
        return expanded != null ? expanded : raw;
    }

    public boolean matchesToken(String token, Map<String, String> prefixes) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        if (token.equals(raw)) {
            return true;
        }
        String resolvedToken;
        if (token.startsWith("<") && token.endsWith(">") && token.length() >= 2) {
            resolvedToken = token.substring(1, token.length() - 1);
        } else {
            resolvedToken = expandPrefixed(token, prefixes);
        }
        return resolvedToken != null && resolvedToken.equals(resolve(prefixes));
    }

    public Set<String> textForms(Map<String, String> prefixes) {
        Set<String> forms = new LinkedHashSet<>();
        String iri = resolve(prefixes);
        if (!raw.startsWith("<") && !raw.contains("://")) {
            forms.add(raw);
        }
        if (!iri.isEmpty()) {
            forms.add("<" + iri + ">");
            forms.add("\"" + iri + "\"");
            forms.add("'" + iri + "'");
        }
        for (Map.Entry<String, String> entry : prefixes.entrySet()) {
            String namespace = entry.getValue();
            if (!namespace.isEmpty() && iri.startsWith(namespace) && iri.length() > namespace.length()) {
                String local = iri.substring(namespace.length());
                if (isPlainLocalName(local)) {
                    forms.add(entry.getKey() + ":" + local);
                }
            }
        }
        forms.remove("");
        return forms;
    }

    static String expandPrefixed(String token, Map<String, String> prefixes) {
        int colon = token.indexOf(':');
        if (colon < 0 || token.startsWith("<") || token.startsWith("_:")) {
            return null;
        }
        String prefix = token.substring(0, colon);
        String namespace = prefixes.get(prefix);
        if (namespace == null) {
            return null;
        }
        return namespace + token.substring(colon + 1).replace("\\", "");
    }

    static boolean isPlainLocalName(String local) {
        if (local.endsWith(".")) {
            return false;
        }
        for (int i = 0; i < local.length(); i++) {
            if (!isNameChar(local.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ':' || c == '%'
                || c > 0x7F;
    }

    public static boolean containsWithBoundaries(String line, String form) {
        if (form.isEmpty()) {
            return false;
        }
        boolean delimited = form.startsWith("<") || form.startsWith("\"") || form.startsWith("'");
        int from = 0;
        while (true) {
            int at = line.indexOf(form, from);
            if (at < 0) {
                return false;
            }
            if (delimited || boundaryAt(line, at, at + form.length())) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean boundaryAt(String line, int start, int end) {
        if (start > 0 && isNameChar(line.charAt(start - 1)) && line.charAt(start - 1) != '.') {
            return false;
        }
        if (end >= line.length()) {
            return true;
        }
        char next = line.charAt(end);
        if (next == '.') {
            return end + 1 >= line.length() || !isNameChar(line.charAt(end + 1));
        }
        return !isNameChar(next);
    }
}
