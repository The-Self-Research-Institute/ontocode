package self.research.ontology.owlEditor.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TurtleSubjectBlockReader {

    static final Pattern PREFIX_DECLARATION = Pattern.compile(
            "(?:@prefix|(?i:prefix))\\s+([^\\s:<>]*):\\s*<([^>]*)>");

    private enum State { NORMAL, IRI, STRING_DOUBLE, STRING_SINGLE, LONG_DOUBLE, LONG_SINGLE }

    private final SourceIdentifier target;
    private final SubjectBlocks.Collector collector;
    private final Map<String, String> prefixes = new HashMap<>();
    private State state = State.NORMAL;
    private boolean inStatement;
    private boolean endAfterIri;
    private int depth;

    private TurtleSubjectBlockReader(SourceIdentifier target, SubjectBlocks.Limits limits) {
        this.target = target;
        this.collector = new SubjectBlocks.Collector(limits);
    }

    public static SubjectBlocks read(BufferedReader reader, String identifier, SubjectBlocks.Limits limits)
            throws IOException {
        TurtleSubjectBlockReader scanner = new TurtleSubjectBlockReader(new SourceIdentifier(identifier), limits);
        String line;
        long lineNo = 0;
        while (!scanner.collector.full() && (line = reader.readLine()) != null) {
            scanner.processLine(line, lineNo++);
        }
        return scanner.collector.result();
    }

    private void processLine(String line, long lineNo) {
        collector.appendLine(line);
        int n = line.length();
        int i = 0;
        while (i < n && !collector.full()) {
            char c = line.charAt(i);
            switch (state) {
                case IRI -> {
                    if (c == '>') {
                        state = State.NORMAL;
                        if (endAfterIri) {
                            endStatement();
                        }
                    }
                    i++;
                }
                case STRING_DOUBLE, STRING_SINGLE -> {
                    char quote = state == State.STRING_DOUBLE ? '"' : '\'';
                    if (c == '\\') {
                        i += 2;
                    } else {
                        if (c == quote) {
                            state = State.NORMAL;
                        }
                        i++;
                    }
                }
                case LONG_DOUBLE, LONG_SINGLE -> {
                    String close = state == State.LONG_DOUBLE ? "\"\"\"" : "'''";
                    if (c == '\\') {
                        i += 2;
                    } else if (line.startsWith(close, i)) {
                        state = State.NORMAL;
                        i += 3;
                    } else {
                        i++;
                    }
                }
                default -> i = normal(line, i, lineNo);
            }
        }
        if (state == State.STRING_DOUBLE || state == State.STRING_SINGLE) {
            state = State.NORMAL;
        }
    }

    private int normal(String line, int i, long lineNo) {
        char c = line.charAt(i);
        if (Character.isWhitespace(c)) {
            return i + 1;
        }
        if (c == '#') {
            return line.length();
        }
        if (!inStatement) {
            startStatement(line, i, lineNo);
        }
        if (c == '<') {
            state = State.IRI;
            return i + 1;
        }
        if (c == '"' || c == '\'') {
            String triple = c == '"' ? "\"\"\"" : "'''";
            if (line.startsWith(triple, i)) {
                state = c == '"' ? State.LONG_DOUBLE : State.LONG_SINGLE;
                return i + 3;
            }
            state = c == '"' ? State.STRING_DOUBLE : State.STRING_SINGLE;
            return i + 1;
        }
        if (c == '[' || c == '(') {
            depth++;
        } else if (c == ']' || c == ')') {
            depth = Math.max(0, depth - 1);
        } else if (c == '.' && depth == 0 && isTerminator(line, i)) {
            endStatement();
        }
        return i + 1;
    }

    static boolean isTerminator(String line, int dot) {
        if (dot + 1 >= line.length()) {
            return true;
        }
        return !SourceIdentifier.isNameChar(line.charAt(dot + 1));
    }

    private void startStatement(String line, int i, long lineNo) {
        inStatement = true;
        depth = 0;
        String token = firstToken(line, i);
        String lower = token.toLowerCase(Locale.ROOT);
        if (token.equals("@prefix") || token.equals("@base") || lower.equals("prefix") || lower.equals("base")) {
            Matcher matcher = PREFIX_DECLARATION.matcher(line);
            if (lower.endsWith("prefix") && matcher.find(i) && matcher.start() == i) {
                prefixes.put(matcher.group(1), matcher.group(2));
            }
            endAfterIri = !token.startsWith("@");
            return;
        }
        if (!target.isBlank() && target.matchesToken(token, prefixes)) {
            collector.start(lineNo, List.of(line));
        }
    }

    static String firstToken(String line, int i) {
        if (line.charAt(i) == '<') {
            int close = line.indexOf('>', i);
            return close < 0 ? line.substring(i) : line.substring(i, close + 1);
        }
        int end = i;
        while (end < line.length()) {
            char c = line.charAt(end);
            if (Character.isWhitespace(c) || "<\"'[](),;#".indexOf(c) >= 0
                    || (c == '.' && isTerminator(line, end))) {
                break;
            }
            end++;
        }
        return line.substring(i, end);
    }

    private void endStatement() {
        inStatement = false;
        endAfterIri = false;
        depth = 0;
        collector.close();
    }
}
