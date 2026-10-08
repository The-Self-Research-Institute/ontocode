package self.research.ontology.owlEditor.service;

import static self.research.ontology.owlEditor.service.TurtleNames.isHex;
import static self.research.ontology.owlEditor.service.TurtleNames.isLocalEscapable;
import static self.research.ontology.owlEditor.service.TurtleNames.isPnChars;
import static self.research.ontology.owlEditor.service.TurtleNames.isPnCharsBase;
import static self.research.ontology.owlEditor.service.TurtleNames.isValidPrefix;
import static self.research.ontology.owlEditor.service.TurtleNames.resolveAgainst;
import static self.research.ontology.owlEditor.service.TurtleNames.unescapeLocalName;
import static self.research.ontology.owlEditor.service.TurtleNames.unescapeUchar;

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

    public TurtleLineScanner fork() {
        TurtleLineScanner copy = new TurtleLineScanner(prefixes, base);
        copy.openLongQuote = openLongQuote;
        copy.directiveState = directiveState;
        copy.pendingPrefix = pendingPrefix;
        return copy;
    }

    public TurtleLineScanner fork(Map<String, String> extraPrefixes) {
        TurtleLineScanner copy = fork();
        extraPrefixes.forEach(copy.prefixes::putIfAbsent);
        return copy;
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
        return new Token(Kind.PNAME, start, end, text, namespace + unescapeLocalName(local), prefix, false, false);
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
}
