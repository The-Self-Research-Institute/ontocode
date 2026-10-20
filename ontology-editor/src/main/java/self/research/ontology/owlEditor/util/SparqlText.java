package self.research.ontology.owlEditor.util;

import java.util.ArrayList;
import java.util.List;

public final class SparqlText {

    private SparqlText() {
    }

    public static boolean hasKeyword(String sparql, String keyword) {
        return indexOfKeyword(sparql, keyword) >= 0;
    }

    public static int indexOfKeyword(String sparql, String keyword) {
        return indexOfKeyword(sparql, keyword, 0);
    }

    public static boolean hasFromClause(String sparql) {
        int from = 0;
        while (true) {
            int at = indexOfKeyword(sparql, "FROM", from);
            if (at < 0) {
                return false;
            }
            int next = skipWhitespace(sparql, at + 4);
            if (next < sparql.length() && sparql.charAt(next) == '<') {
                return true;
            }
            from = at + 4;
        }
    }

    public static String replaceFirstKeyword(String sparql, String keyword, String replacement) {
        int at = indexOfKeyword(sparql, keyword);
        return at < 0 ? sparql : sparql.substring(0, at) + replacement + sparql.substring(at + keyword.length());
    }

    public static String removeFromClauses(String sparql) {
        StringBuilder out = new StringBuilder(sparql.length());
        int copied = 0;
        int from = 0;
        while (true) {
            int at = indexOfKeyword(sparql, "FROM", from);
            if (at < 0) {
                break;
            }
            int iri = skipWhitespace(sparql, at + 4);
            int end = iri < sparql.length() && sparql.charAt(iri) == '<' ? iriEnd(sparql, iri) : -1;
            if (end > 0) {
                out.append(sparql, copied, at).append(' ');
                copied = end;
                from = end;
            } else {
                from = at + 4;
            }
        }
        return out.append(sparql, copied, sparql.length()).toString();
    }

    public static int indexOfOpenBrace(String sparql, int from) {
        int i = from;
        while (i < sparql.length()) {
            int skipped = skipNonCode(sparql, i);
            if (skipped != i) {
                i = skipped;
            } else if (sparql.charAt(i) == '{') {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }

    public static List<String> splitStatements(String sparql) {
        List<String> statements = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int i = 0;
        while (i < sparql.length()) {
            int skipped = skipNonCode(sparql, i);
            if (skipped != i) {
                i = skipped;
                continue;
            }
            char c = sparql.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == ';' && depth == 0) {
                statements.add(sparql.substring(start, i).trim());
                start = i + 1;
            }
            i++;
        }
        if (start < sparql.length()) {
            statements.add(sparql.substring(start).trim());
        }
        return statements;
    }

    private static int indexOfKeyword(String sparql, String keyword, int from) {
        int length = keyword.length();
        int i = from;
        while (i < sparql.length()) {
            int skipped = skipNonCode(sparql, i);
            if (skipped != i) {
                i = skipped;
            } else if (sparql.regionMatches(true, i, keyword, 0, length)
                    && (i == 0 || !isNameChar(sparql.charAt(i - 1)))
                    && (i + length >= sparql.length() || !isNameChar(sparql.charAt(i + length)))) {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }

    private static int skipNonCode(String s, int i) {
        char c = s.charAt(i);
        if (c == '"' || c == '\'') {
            return skipQuoted(s, i);
        }
        if (c == '<') {
            int end = iriEnd(s, i);
            return end > 0 ? end : i;
        }
        if (c == '#') {
            int eol = s.indexOf('\n', i);
            return eol < 0 ? s.length() : eol + 1;
        }
        return i;
    }

    private static int skipQuoted(String s, int start) {
        char quote = s.charAt(start);
        int n = s.length();
        boolean triple = start + 2 < n && s.charAt(start + 1) == quote && s.charAt(start + 2) == quote;
        int i = start + (triple ? 3 : 1);
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote && (!triple || (i + 2 < n && s.charAt(i + 1) == quote && s.charAt(i + 2) == quote))) {
                return i + (triple ? 3 : 1);
            } else {
                i++;
            }
        }
        return n;
    }

    private static int iriEnd(String s, int start) {
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '>') {
                return i + 1;
            }
            if (Character.isWhitespace(c) || c == '<' || c == '"' || c == '{' || c == '}') {
                return -1;
            }
        }
        return -1;
    }

    private static int skipWhitespace(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '-' || c == '?' || c == '$';
    }
}
