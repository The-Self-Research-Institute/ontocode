package self.research.ontology.owlEditor.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RdfXmlSubjectBlockReader {

    static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    static final Pattern ATTRIBUTE = Pattern.compile("([\\w.:\\-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    static final Pattern ENTITY = Pattern.compile("<!ENTITY\\s+([\\w.\\-]+)\\s+(?:\"([^\"]*)\"|'([^']*)')");

    private static final int MAX_MARKUP_CHARS = 65_536;
    private static final int MAX_PENDING_LINES = 1_000;

    private enum State { TEXT, TAG, TAG_DOUBLE, TAG_SINGLE, COMMENT, CDATA, PI, DOCTYPE }

    private final SourceIdentifier target;
    private final SubjectBlocks.Collector collector;
    private final Map<String, String> namespaces = new HashMap<>();
    private final Map<String, String> entities = new HashMap<>();
    private final Set<String> rdfPrefixes = new HashSet<>(Set.of("rdf"));
    private final List<String> pendingLines = new ArrayList<>();
    private final StringBuilder markup = new StringBuilder();
    private State state = State.TEXT;
    private String base;
    private int depth;
    private int blockDepth = -1;
    private int doctypeBrackets;
    private long markupStartLine;

    private RdfXmlSubjectBlockReader(SourceIdentifier target, SubjectBlocks.Limits limits) {
        this.target = target;
        this.collector = new SubjectBlocks.Collector(limits);
    }

    public static SubjectBlocks read(BufferedReader reader, String identifier, SubjectBlocks.Limits limits)
            throws IOException {
        RdfXmlSubjectBlockReader scanner = new RdfXmlSubjectBlockReader(new SourceIdentifier(identifier), limits);
        String line;
        long lineNo = 0;
        while (!scanner.collector.full() && (line = reader.readLine()) != null) {
            scanner.processLine(line, lineNo++);
        }
        return scanner.collector.result();
    }

    private void processLine(String line, long lineNo) {
        collector.appendLine(line);
        if (state == State.TAG || state == State.TAG_DOUBLE || state == State.TAG_SINGLE) {
            if (pendingLines.size() < MAX_PENDING_LINES) {
                pendingLines.add(line);
            }
            appendMarkup(' ');
        }
        int n = line.length();
        int i = 0;
        while (i < n && !collector.full()) {
            char c = line.charAt(i);
            switch (state) {
                case TEXT -> i = text(line, i, lineNo);
                case TAG -> {
                    appendMarkup(c);
                    if (c == '"') {
                        state = State.TAG_DOUBLE;
                    } else if (c == '\'') {
                        state = State.TAG_SINGLE;
                    } else if (c == '>') {
                        state = State.TEXT;
                        finishTag(lineNo);
                    }
                    i++;
                }
                case TAG_DOUBLE, TAG_SINGLE -> {
                    appendMarkup(c);
                    if (c == (state == State.TAG_DOUBLE ? '"' : '\'')) {
                        state = State.TAG;
                    }
                    i++;
                }
                case COMMENT -> i = skipUntil(line, i, "-->");
                case CDATA -> i = skipUntil(line, i, "]]>");
                case PI -> i = skipUntil(line, i, "?>");
                default -> i = doctype(line, i);
            }
        }
        if (state == State.DOCTYPE) {
            appendMarkup(' ');
        }
    }

    private int text(String line, int i, long lineNo) {
        if (line.charAt(i) != '<') {
            return i + 1;
        }
        if (line.startsWith("<!--", i)) {
            state = State.COMMENT;
            return i + 4;
        }
        if (line.startsWith("<![CDATA[", i)) {
            state = State.CDATA;
            return i + 9;
        }
        if (line.startsWith("<?", i)) {
            state = State.PI;
            return i + 2;
        }
        markup.setLength(0);
        if (line.startsWith("<!DOCTYPE", i)) {
            state = State.DOCTYPE;
            doctypeBrackets = 0;
            return i;
        }
        state = State.TAG;
        markupStartLine = lineNo;
        pendingLines.clear();
        pendingLines.add(line);
        return i;
    }

    private int skipUntil(String line, int i, String close) {
        int at = line.indexOf(close, i);
        if (at < 0) {
            return line.length();
        }
        state = State.TEXT;
        return at + close.length();
    }

    private int doctype(String line, int i) {
        char c = line.charAt(i);
        appendMarkup(c);
        if (c == '[') {
            doctypeBrackets++;
        } else if (c == ']') {
            doctypeBrackets = Math.max(0, doctypeBrackets - 1);
        } else if (c == '>' && doctypeBrackets == 0) {
            Matcher matcher = ENTITY.matcher(markup);
            while (matcher.find()) {
                entities.put(matcher.group(1), matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
            }
            state = State.TEXT;
        }
        return i + 1;
    }

    private void appendMarkup(char c) {
        if (markup.length() < MAX_MARKUP_CHARS) {
            markup.append(c);
        }
    }

    private void finishTag(long lineNo) {
        String tag = markup.toString();
        if (tag.startsWith("</")) {
            depth = Math.max(0, depth - 1);
            if (blockDepth >= 0 && depth == blockDepth) {
                closeBlock();
            }
            return;
        }
        boolean selfClosing = tag.endsWith("/>");
        String about = null;
        Matcher matcher = ATTRIBUTE.matcher(tag);
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = decode(matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
            if (name.startsWith("xmlns:")) {
                String prefix = name.substring(6);
                namespaces.put(prefix, value);
                if (RDF_NS.equals(value)) {
                    rdfPrefixes.add(prefix);
                }
            } else if (name.equals("xml:base") && base == null) {
                base = value;
            } else if (name.endsWith(":about") && rdfPrefixes.contains(name.substring(0, name.length() - 6))) {
                about = value;
            }
        }
        if (about != null && blockDepth < 0 && !target.isBlank() && matches(about)) {
            collector.start(markupStartLine, pendingLines);
            if (collector.open()) {
                blockDepth = depth;
            }
        }
        if (selfClosing) {
            if (blockDepth >= 0 && depth == blockDepth) {
                closeBlock();
            }
        } else {
            depth++;
        }
    }

    private void closeBlock() {
        collector.close();
        blockDepth = -1;
    }

    private boolean matches(String about) {
        String resolved = about;
        if (about.startsWith("#") && base != null) {
            int hash = base.indexOf('#');
            resolved = (hash >= 0 ? base.substring(0, hash) : base) + about;
        }
        return target.matchesToken("<" + resolved + ">", namespaces) || target.raw().equals(about);
    }

    private String decode(String value) {
        if (value.indexOf('&') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            int semi = c == '&' ? value.indexOf(';', i) : -1;
            if (semi > i) {
                String name = value.substring(i + 1, semi);
                String replacement = switch (name) {
                    case "amp" -> "&";
                    case "lt" -> "<";
                    case "gt" -> ">";
                    case "quot" -> "\"";
                    case "apos" -> "'";
                    default -> entities.get(name);
                };
                if (replacement != null) {
                    out.append(replacement);
                    i = semi + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
