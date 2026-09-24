package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.common.net.ParsedIRI;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TurtleLineScanner {

    public enum Kind { IRI, PNAME, A, LITERAL, BNODE, PUNCT, OTHER }

    public record Token(Kind kind, int start, int end, String text, String iri, String prefix,
                        boolean directive, boolean unresolved) {
        public boolean isTerm() {
            return (kind == Kind.IRI || kind == Kind.PNAME) && !directive;
        }
    }

    private enum DirectiveState { NONE, EXPECT_NAMESPACE, EXPECT_PREFIX_IRI, EXPECT_BASE_IRI }

    private final Map<String, String> prefixes;
    private String base;
    private String openLongQuote;
    private DirectiveState directiveState = DirectiveState.NONE;
    private String pendingPrefix;

    public TurtleLineScanner() {
        this(new HashMap<>(), null);
    }

    public TurtleLineScanner(Map<String, String> initialPrefixes, String initialBase) {
        this.prefixes = new HashMap<>(initialPrefixes);
        this.base = initialBase;
    }

    public Map<String, String> prefixes() {
        return prefixes;
    }

    public String base() {
        return base;
    }

    public List<Token> scan(String line) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        if (openLongQuote != null) {
            int close = findLongStringEnd(line, 0, openLongQuote);
            if (close < 0) {
                return tokens;
            }
            openLongQuote = null;
            i = close;
            tokens.add(literal(line, 0, close));
        }
        while (i < line.length()) {
            int before = tokens.size();
            i = scanOne(line, i, tokens);
            for (int t = before; t < tokens.size(); t++) {
                if (!tokens.get(t).directive()) {
                    directiveState = DirectiveState.NONE;
                }
            }
        }
        return tokens;
    }

    private int scanOne(String line, int i, List<Token> tokens) {
        int n = line.length();
        char c = line.charAt(i);
        if (Character.isWhitespace(c)) {
            return i + 1;
        }
        if (c == '#') {
            return n;
        }
        if (c == '"' || c == '\'') {
            return scanString(line, i, tokens);
        }
        if (c == '<') {
            if (i + 1 < n && line.charAt(i + 1) == '<') {
                tokens.add(punct(line, i, i + 2));
                return i + 2;
            }
            int close = findIriEnd(line, i + 1);
            if (close < 0) {
                tokens.add(punct(line, i, i + 1));
                return i + 1;
            }
            tokens.add(iriToken(line, i, close + 1));
            return close + 1;
        }
        if ((c == '>' || c == '^') && i + 1 < n && line.charAt(i + 1) == c) {
            tokens.add(punct(line, i, i + 2));
            return i + 2;
        }
        if (c == '@') {
            int end = i + 1;
            while (end < n && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '-')) {
                end++;
            }
            String word = line.substring(i, end);
            boolean directive = startDirectiveIfKeyword(word.substring(1), true);
            tokens.add(new Token(Kind.OTHER, i, end, word, null, null, directive, false));
            return end;
        }
        if (isNumberStart(line, i)) {
            return scanNumber(line, i, tokens);
        }
        int cp = line.codePointAt(i);
        if (isPnCharsBase(cp) || c == ':' || c == '_') {
            return scanWord(line, i, tokens);
        }
        tokens.add(punct(line, i, i + 1));
        return i + 1;
    }

    private Token punct(String line, int start, int end) {
        return new Token(Kind.PUNCT, start, end, line.substring(start, end), null, null, false, false);
    }

    private Token literal(String line, int start, int end) {
        return new Token(Kind.LITERAL, start, end, line.substring(start, end), null, null, false, false);
    }

    private int scanString(String line, int start, List<Token> tokens) {
        char q = line.charAt(start);
        String triple = String.valueOf(q).repeat(3);
        if (line.startsWith(triple, start)) {
            int close = findLongStringEnd(line, start + 3, triple);
            if (close < 0) {
                openLongQuote = triple;
                tokens.add(literal(line, start, line.length()));
                return line.length();
            }
            tokens.add(literal(line, start, close));
            return close;
        }
        int i = start + 1;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == q) {
                tokens.add(literal(line, start, i + 1));
                return i + 1;
            }
            i++;
        }
        tokens.add(literal(line, start, line.length()));
        return line.length();
    }

    private int findLongStringEnd(String line, int from, String triple) {
        int i = from;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (line.startsWith(triple, i)) {
                int end = i + 3;
                while (end < line.length() && line.charAt(end) == triple.charAt(0)) {
                    end++;
                }
                return end;
            }
            i++;
        }
        return -1;
    }

    private int findIriEnd(String line, int from) {
        for (int i = from; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '>') {
                return i;
            }
            if (c <= ' ' || c == '<' || c == '"' || c == '{' || c == '}' || c == '|' || c == '^' || c == '`') {
                return -1;
            }
        }
        return -1;
    }

    private Token iriToken(String line, int start, int end) {
        String value = unescapeUchar(line.substring(start + 1, end - 1));
        String resolved = resolveAgainst(base, value);
        String text = line.substring(start, end);
        if (directiveState == DirectiveState.EXPECT_PREFIX_IRI) {
            if (resolved != null) {
                prefixes.put(pendingPrefix, resolved);
            }
            return new Token(Kind.IRI, start, end, text, resolved, null, true, resolved == null);
        }
        if (directiveState == DirectiveState.EXPECT_BASE_IRI) {
            if (resolved != null) {
                base = resolved;
            }
            return new Token(Kind.IRI, start, end, text, resolved, null, true, resolved == null);
        }
        return new Token(Kind.IRI, start, end, text, resolved, null, false, resolved == null);
    }

    private boolean isNumberStart(String line, int i) {
        char c = line.charAt(i);
        if (Character.isDigit(c)) {
            return true;
        }
        int n = line.length();
        if ((c == '+' || c == '-') && i + 1 < n) {
            char next = line.charAt(i + 1);
            return Character.isDigit(next) || (next == '.' && i + 2 < n && Character.isDigit(line.charAt(i + 2)));
        }
        return c == '.' && i + 1 < n && Character.isDigit(line.charAt(i + 1));
    }

    private int scanNumber(String line, int start, List<Token> tokens) {
        int i = start;
        int n = line.length();
        if (line.charAt(i) == '+' || line.charAt(i) == '-') {
            i++;
        }
        while (i < n && Character.isDigit(line.charAt(i))) {
            i++;
        }
        if (i + 1 < n && line.charAt(i) == '.' && Character.isDigit(line.charAt(i + 1))) {
            i++;
            while (i < n && Character.isDigit(line.charAt(i))) {
                i++;
            }
        }
        if (i < n && (line.charAt(i) == 'e' || line.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < n && (line.charAt(j) == '+' || line.charAt(j) == '-')) {
                j++;
            }
            if (j < n && Character.isDigit(line.charAt(j))) {
                i = j;
                while (i < n && Character.isDigit(line.charAt(i))) {
                    i++;
                }
            }
        }
        tokens.add(literal(line, start, i));
        return i;
    }

    private int scanWord(String line, int start, List<Token> tokens) {
        int n = line.length();
        int i = start;
        while (i < n) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < n && isLocalEscapable(line.charAt(i + 1))) {
                i += 2;
                continue;
            }
            if (c == '%' && i + 2 < n && isHex(line.charAt(i + 1)) && isHex(line.charAt(i + 2))) {
                i += 3;
                continue;
            }
            int cp = line.codePointAt(i);
            if (isPnChars(cp) || c == '.' || c == ':') {
                i += Character.charCount(cp);
                continue;
            }
            break;
        }
        int end = i;
        while (end > start && line.charAt(end - 1) == '.' && !isEscapedAt(line, start, end - 1)) {
            end--;
        }
        if (end == start) {
            tokens.add(punct(line, start, start + 1));
            return start + 1;
        }
        tokens.add(classifyWord(line.substring(start, end), start, end));
        return end;
    }

    private boolean isEscapedAt(String line, int start, int index) {
        int backslashes = 0;
        int j = index - 1;
        while (j >= start && line.charAt(j) == '\\') {
            backslashes++;
            j--;
        }
        return backslashes % 2 == 1;
    }

    private Token classifyWord(String text, int start, int end) {
        if (text.startsWith("_:")) {
            return new Token(Kind.BNODE, start, end, text, null, null, false, false);
        }
        int colon = text.indexOf(':');
        if (colon < 0) {
            if (text.equals("a")) {
                return new Token(Kind.A, start, end, text, null, null, false, false);
            }
            if (text.equals("true") || text.equals("false")) {
                return new Token(Kind.LITERAL, start, end, text, null, null, false, false);
            }
            boolean directive = startDirectiveIfKeyword(text, false);
            return new Token(Kind.OTHER, start, end, text, null, null, directive, false);
        }
        String prefix = text.substring(0, colon);
        String local = text.substring(colon + 1);
        if (!isValidPrefix(prefix)) {
            return new Token(Kind.OTHER, start, end, text, null, null, false, false);
        }
        if (directiveState == DirectiveState.EXPECT_NAMESPACE && local.isEmpty()) {
            pendingPrefix = prefix;
            directiveState = DirectiveState.EXPECT_PREFIX_IRI;
            return new Token(Kind.PNAME, start, end, text, null, prefix, true, false);
        }
        String namespace = prefixes.get(prefix);
        if (namespace == null) {
            return new Token(Kind.PNAME, start, end, text, null, prefix, false, true);
        }
        return new Token(Kind.PNAME, start, end, text, namespace + unescapeLocal(local), prefix, false, false);
    }

    private boolean startDirectiveIfKeyword(String word, boolean atForm) {
        String lower = word.toLowerCase(Locale.ROOT);
        boolean matches = atForm ? (word.equals("prefix") || word.equals("base"))
                : (lower.equals("prefix") || lower.equals("base"));
        if (matches) {
            directiveState = lower.equals("prefix") ? DirectiveState.EXPECT_NAMESPACE : DirectiveState.EXPECT_BASE_IRI;
        }
        return matches;
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

    private static String unescapeLocal(String local) {
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

    private static String unescapeUchar(String raw) {
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

    private static boolean isLocalEscapable(char c) {
        return "_~.-!$&'()*+,;=/?#@%".indexOf(c) >= 0;
    }

    private static boolean isHex(char c) {
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
