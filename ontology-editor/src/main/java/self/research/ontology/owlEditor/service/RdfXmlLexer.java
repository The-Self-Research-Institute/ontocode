package self.research.ontology.owlEditor.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RdfXmlLexer {

    private static final int MAX_DOCTYPE_CHARS = 4 * 1024 * 1024;
    private static final Pattern ENTITY_DECL =
            Pattern.compile("<!ENTITY\\s+([^\\s%\"'>]+)\\s+(\"([^\"]*)\"|'([^']*)')\\s*>");

    enum Mode { TEXT, COMMENT, CDATA, PI, DOCTYPE, TAG }

    private enum Phase { NAME, SPACE, ATTR_NAME, EQ, VALUE_START, VALUE }

    interface Tags {
        void tag(boolean endTag, String name, boolean selfClosing);
    }

    static final class Attr {
        String name;
        long nameLine;
        int nameStart;
        int nameEnd;
        final StringBuilder value = new StringBuilder();
        long valueLine;
        int valueStart;
        int valueEnd;
        char quote;
        boolean multiLine;
    }

    private final Map<String, String> entities;
    private final Tags tags;
    Mode mode = Mode.TEXT;
    private Phase phase;
    private boolean endTag;
    private boolean selfClosingPending;
    private final StringBuilder name = new StringBuilder();
    long nameLine;
    int nameStart;
    int nameEnd;
    final List<Attr> attrs = new ArrayList<>();
    private Attr currentAttr;
    private final StringBuilder doctype = new StringBuilder();
    private boolean doctypeInSubset;
    private char doctypeQuote;
    String unsupportedReason;

    RdfXmlLexer(Map<String, String> entities, Tags tags) {
        this.entities = entities;
        this.tags = tags;
    }

    boolean atSafeBoundary() {
        return mode != Mode.TAG && mode != Mode.DOCTYPE;
    }

    void feed(long lineNo, String line) {
        int i = 0;
        int n = line.length();
        while (i <= n) {
            switch (mode) {
                case TEXT -> {
                    int lt = line.indexOf('<', i);
                    if (lt < 0) {
                        return;
                    }
                    i = openMarkup(line, lt, lineNo);
                }
                case COMMENT -> {
                    int end = line.indexOf("-->", i);
                    if (end < 0) {
                        return;
                    }
                    mode = Mode.TEXT;
                    i = end + 3;
                }
                case CDATA -> {
                    int end = line.indexOf("]]>", i);
                    if (end < 0) {
                        return;
                    }
                    mode = Mode.TEXT;
                    i = end + 3;
                }
                case PI -> {
                    int end = line.indexOf('>', i);
                    if (end < 0) {
                        return;
                    }
                    mode = Mode.TEXT;
                    i = end + 1;
                }
                case DOCTYPE -> i = scanDoctype(line, i);
                case TAG -> i = scanTag(line, i, lineNo);
            }
            if (i >= n && mode != Mode.TAG && mode != Mode.DOCTYPE) {
                return;
            }
            if (i >= n) {
                endOfLineInMarkup();
                return;
            }
        }
    }

    private void endOfLineInMarkup() {
        if (mode == Mode.DOCTYPE) {
            doctype.append('\n');
        } else if (mode == Mode.TAG && phase == Phase.VALUE && currentAttr != null) {
            currentAttr.value.append('\n');
            currentAttr.multiLine = true;
        }
    }

    private int openMarkup(String line, int lt, long lineNo) {
        if (line.startsWith("<!--", lt)) {
            mode = Mode.COMMENT;
            return lt + 4;
        }
        if (line.startsWith("<![CDATA[", lt)) {
            mode = Mode.CDATA;
            return lt + 9;
        }
        if (line.startsWith("<!DOCTYPE", lt)) {
            mode = Mode.DOCTYPE;
            doctype.setLength(0);
            doctypeInSubset = false;
            doctypeQuote = 0;
            return lt + 9;
        }
        if (line.startsWith("<?", lt) || line.startsWith("<!", lt)) {
            mode = Mode.PI;
            return lt + 2;
        }
        mode = Mode.TAG;
        attrs.clear();
        currentAttr = null;
        name.setLength(0);
        selfClosingPending = false;
        phase = Phase.NAME;
        if (line.startsWith("</", lt)) {
            endTag = true;
            nameLine = lineNo;
            nameStart = lt + 2;
            return lt + 2;
        }
        endTag = false;
        nameLine = lineNo;
        nameStart = lt + 1;
        return lt + 1;
    }

    private int scanDoctype(String line, int from) {
        int i = from;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (doctype.length() > MAX_DOCTYPE_CHARS) {
                unsupportedReason = "the DOCTYPE declaration is too large to scan";
                mode = Mode.TEXT;
                return line.length();
            }
            if (doctypeQuote != 0) {
                if (c == doctypeQuote) {
                    doctypeQuote = 0;
                }
            } else if (c == '"' || c == '\'') {
                doctypeQuote = c;
            } else if (c == '[') {
                doctypeInSubset = true;
            } else if (c == ']') {
                doctypeInSubset = false;
            } else if (c == '>' && !doctypeInSubset) {
                parseEntities();
                mode = Mode.TEXT;
                return i + 1;
            }
            doctype.append(c);
            i++;
        }
        return i;
    }

    private void parseEntities() {
        Map<String, String> declared = new HashMap<>();
        Matcher matcher = ENTITY_DECL.matcher(doctype);
        while (matcher.find()) {
            String value = matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            declared.putIfAbsent(matcher.group(1), value);
        }
        for (int round = 0; round < 5; round++) {
            boolean changed = false;
            for (Map.Entry<String, String> entry : declared.entrySet()) {
                if (entry.getValue().indexOf('&') >= 0) {
                    String expanded = RdfXmlScanner.decode(entry.getValue(), declared);
                    if (expanded != null && !expanded.equals(entry.getValue())) {
                        entry.setValue(expanded);
                        changed = true;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        entities.putAll(declared);
        doctype.setLength(0);
    }

    private int scanTag(String line, int from, long lineNo) {
        int i = from;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            i = switch (phase) {
                case NAME -> scanName(c, i);
                case SPACE -> scanSpace(c, i, lineNo);
                case ATTR_NAME -> scanAttrName(c, i);
                case EQ -> scanEq(c, i);
                case VALUE_START -> scanValueStart(c, i, lineNo);
                case VALUE -> scanValue(line, i);
            };
            if (mode != Mode.TAG) {
                return i;
            }
        }
        if (phase == Phase.NAME && !name.isEmpty()) {
            nameEnd = n;
            phase = Phase.SPACE;
        } else if (phase == Phase.ATTR_NAME) {
            currentAttr.nameEnd = n;
            phase = Phase.EQ;
        }
        return n;
    }

    private int scanName(char c, int i) {
        if (Character.isWhitespace(c) || c == '>' || c == '/' || c == '=') {
            if (name.isEmpty()) {
                mode = Mode.TEXT;
                return i;
            }
            nameEnd = i;
            phase = Phase.SPACE;
            return i;
        }
        name.append(c);
        return i + 1;
    }

    private int scanSpace(char c, int i, long lineNo) {
        if (Character.isWhitespace(c)) {
            return i + 1;
        } else if (c == '>') {
            finishTag(selfClosingPending);
            return i + 1;
        } else if (c == '/' || c == '?') {
            selfClosingPending = true;
            return i + 1;
        }
        currentAttr = new Attr();
        currentAttr.name = "";
        currentAttr.nameLine = lineNo;
        currentAttr.nameStart = i;
        phase = Phase.ATTR_NAME;
        return i;
    }

    private int scanAttrName(char c, int i) {
        if (Character.isWhitespace(c) || c == '=' || c == '>' || c == '/') {
            currentAttr.nameEnd = i;
            phase = Phase.EQ;
            return i;
        }
        currentAttr.name += c;
        return i + 1;
    }

    private int scanEq(char c, int i) {
        if (Character.isWhitespace(c)) {
            return i + 1;
        } else if (c == '=') {
            phase = Phase.VALUE_START;
            return i + 1;
        }
        phase = Phase.SPACE;
        return i;
    }

    private int scanValueStart(char c, int i, long lineNo) {
        if (Character.isWhitespace(c)) {
            return i + 1;
        } else if (c == '"' || c == '\'') {
            currentAttr.quote = c;
            currentAttr.valueLine = lineNo;
            currentAttr.valueStart = i + 1;
            phase = Phase.VALUE;
            return i + 1;
        }
        phase = Phase.SPACE;
        return i;
    }

    private int scanValue(String line, int i) {
        int close = line.indexOf(currentAttr.quote, i);
        if (close < 0) {
            currentAttr.value.append(line, i, line.length());
            return line.length();
        }
        currentAttr.value.append(line, i, close);
        currentAttr.valueEnd = close;
        attrs.add(currentAttr);
        currentAttr = null;
        phase = Phase.SPACE;
        return close + 1;
    }

    private void finishTag(boolean selfClosing) {
        mode = Mode.TEXT;
        tags.tag(endTag, name.toString(), selfClosing);
    }
}
