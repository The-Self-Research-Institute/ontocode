package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.common.net.ParsedIRI;

public final class TurtleNames {

    private TurtleNames() {
    }

    public static boolean isValidPrefix(String prefix) {
        if (prefix.isEmpty()) {
            return true;
        }
        int first = prefix.codePointAt(0);
        if (!isPnCharsBase(first) || prefix.endsWith(".")) {
            return false;
        }
        int i = Character.charCount(first);
        while (i < prefix.length()) {
            int cp = prefix.codePointAt(i);
            if (!isPnChars(cp) && cp != '.') {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    public static boolean isSimpleLocalName(String local) {
        if (local.isEmpty()) {
            return true;
        }
        int first = local.codePointAt(0);
        if (!(isPnCharsU(first) || first == ':' || Character.isDigit(first)) || local.endsWith(".")) {
            return false;
        }
        int i = Character.charCount(first);
        while (i < local.length()) {
            int cp = local.codePointAt(i);
            if (!isPnChars(cp) && cp != '.' && cp != ':') {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    public static String resolveAgainst(String base, String value) {
        try {
            ParsedIRI parsed = new ParsedIRI(value);
            if (parsed.isAbsolute()) {
                return value;
            }
            if (base == null) {
                return null;
            }
            return new ParsedIRI(base).resolve(value);
        } catch (Exception e) {
            return null;
        }
    }

    public static String unescapeLocalName(String local) {
        if (local.indexOf('\\') < 0) {
            return local;
        }
        StringBuilder sb = new StringBuilder(local.length());
        for (int i = 0; i < local.length(); i++) {
            char c = local.charAt(i);
            if (c == '\\' && i + 1 < local.length()) {
                sb.append(local.charAt(i + 1));
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unescapeUchar(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length() && (raw.charAt(i + 1) == 'u' || raw.charAt(i + 1) == 'U')) {
                int len = raw.charAt(i + 1) == 'u' ? 4 : 8;
                if (i + 2 + len <= raw.length()) {
                    try {
                        sb.appendCodePoint(Integer.parseInt(raw.substring(i + 2, i + 2 + len), 16));
                        i += 2 + len;
                        continue;
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    static boolean isLocalEscapable(char c) {
        return "_~.-!$&'()*+,;=/?#@%".indexOf(c) >= 0;
    }

    static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    public static boolean isPnCharsBase(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z')
                || (cp >= 0x00C0 && cp <= 0x00D6) || (cp >= 0x00D8 && cp <= 0x00F6)
                || (cp >= 0x00F8 && cp <= 0x02FF) || (cp >= 0x0370 && cp <= 0x037D)
                || (cp >= 0x037F && cp <= 0x1FFF) || (cp >= 0x200C && cp <= 0x200D)
                || (cp >= 0x2070 && cp <= 0x218F) || (cp >= 0x2C00 && cp <= 0x2FEF)
                || (cp >= 0x3001 && cp <= 0xD7FF) || (cp >= 0xF900 && cp <= 0xFDCF)
                || (cp >= 0xFDF0 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0xEFFFF);
    }

    public static boolean isPnCharsU(int cp) {
        return isPnCharsBase(cp) || cp == '_';
    }

    public static boolean isPnChars(int cp) {
        return isPnCharsU(cp) || cp == '-' || (cp >= '0' && cp <= '9') || cp == 0x00B7
                || (cp >= 0x0300 && cp <= 0x036F) || (cp >= 0x203F && cp <= 0x2040);
    }
}
