package self.research.ontology.owlEditor.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RdfXmlScanner {

    public static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    public static final String XML_NS = "http://www.w3.org/XML/1998/namespace";

    private static final int MAX_DOCTYPE_CHARS = 4 * 1024 * 1024;
    private static final Pattern ENTITY_DECL =
            Pattern.compile("<!ENTITY\\s+([^\\s%\"'>]+)\\s+(\"([^\"]*)\"|'([^']*)')\\s*>");
    private static final Set<String> SYNTAX_ATTRIBUTES =
            Set.of("about", "resource", "ID", "datatype", "nodeID", "parseType", "bagID", "aboutEach", "aboutEachPrefix");

    public enum OccurrenceKind { ELEMENT_NAME, ATTRIBUTE_NAME, ABOUT, RESOURCE, DATATYPE, ID, TYPE_VALUE }

    public record Occurrence(long line, int start, int end, OccurrenceKind kind, String iri, String rawText,
                             char quote, Map<String, String> namespaces, String base, boolean resolvable,
                             boolean multiLine) {
        public boolean isName() {
            return kind == OccurrenceKind.ELEMENT_NAME || kind == OccurrenceKind.ATTRIBUTE_NAME;
        }
    }

    public interface Listener {
        default void occurrence(Occurrence occurrence) {
        }

        default void reference(String iri) {
        }

        default void subject(String iri) {
        }

        default void declared(String subject, String type) {
        }
    }

    private enum Mode { TEXT, COMMENT, CDATA, PI, DOCTYPE, TAG }

    private enum Phase { NAME, SPACE, ATTR_NAME, EQ, VALUE_START, VALUE }

    private enum Role { NODE, PROPERTY, LITERAL }

    private record Frame(Map<String, String> namespaces, String base, Role childRole, String subject,
                         boolean emitNames) {}

    private static final class Attr {
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

    private final Listener listener;
    private final Map<String, String> entities = new HashMap<>();
    private final Deque<Frame> stack = new ArrayDeque<>();
    private Mode mode = Mode.TEXT;
    private Phase phase;
    private boolean endTag;
    private boolean selfClosingPending;
    private final StringBuilder name = new StringBuilder();
    private long nameLine;
    private int nameStart;
    private int nameEnd;
    private final List<Attr> attrs = new ArrayList<>();
    private Attr currentAttr;
    private final StringBuilder doctype = new StringBuilder();
    private boolean doctypeInSubset;
    private char doctypeQuote;
    private boolean rootSeen;
    private Map<String, String> rootNamespaces;
    private String rootBase;
    private String unsupportedReason;

    public RdfXmlScanner(Listener listener) {
        this(Map.of(), null, Map.of(), listener);
    }

    public RdfXmlScanner(Map<String, String> initialNamespaces, String initialBase,
                         Map<String, String> initialEntities, Listener listener) {
        this.listener = listener;
        entities.put("amp", "&");
        entities.put("lt", "<");
        entities.put("gt", ">");
        entities.put("quot", "\"");
        entities.put("apos", "'");
        entities.putAll(initialEntities);
        Map<String, String> namespaces = new HashMap<>(initialNamespaces);
        namespaces.put("xml", XML_NS);
        stack.push(new Frame(namespaces, initialBase, Role.NODE, null, false));
    }

    public boolean atSafeBoundary() {
        return mode != Mode.TAG && mode != Mode.DOCTYPE;
    }

    public boolean rootSeen() {
        return rootSeen;
    }

    public Map<String, String> rootNamespaces() {
        return rootNamespaces == null ? stack.getLast().namespaces() : rootNamespaces;
    }

    public String rootBase() {
        return rootNamespaces == null ? stack.getLast().base() : rootBase;
    }

    public Map<String, String> entities() {
        return entities;
    }

    public String unsupportedReason() {
        return unsupportedReason;
    }

    public void feed(long lineNo, String line) {
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
                    String expanded = decode(entry.getValue(), declared);
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
            switch (phase) {
                case NAME -> {
                    if (Character.isWhitespace(c) || c == '>' || c == '/' || c == '=') {
                        if (name.isEmpty()) {
                            mode = Mode.TEXT;
                            return i;
                        }
                        nameEnd = i;
                        phase = Phase.SPACE;
                        continue;
                    }
                    name.append(c);
                    i++;
                }
                case SPACE -> {
                    if (Character.isWhitespace(c)) {
                        i++;
                    } else if (c == '>') {
                        finishTag(selfClosingPending);
                        return i + 1;
                    } else if (c == '/' || c == '?') {
                        selfClosingPending = true;
                        i++;
                    } else {
                        currentAttr = new Attr();
                        currentAttr.name = "";
                        currentAttr.nameLine = lineNo;
                        currentAttr.nameStart = i;
                        phase = Phase.ATTR_NAME;
                    }
                }
                case ATTR_NAME -> {
                    if (Character.isWhitespace(c) || c == '=' || c == '>' || c == '/') {
                        currentAttr.nameEnd = i;
                        phase = Phase.EQ;
                        continue;
                    }
                    currentAttr.name += c;
                    i++;
                }
                case EQ -> {
                    if (Character.isWhitespace(c)) {
                        i++;
                    } else if (c == '=') {
                        phase = Phase.VALUE_START;
                        i++;
                    } else {
                        phase = Phase.SPACE;
                    }
                }
                case VALUE_START -> {
                    if (Character.isWhitespace(c)) {
                        i++;
                    } else if (c == '"' || c == '\'') {
                        currentAttr.quote = c;
                        currentAttr.valueLine = lineNo;
                        currentAttr.valueStart = i + 1;
                        phase = Phase.VALUE;
                        i++;
                    } else {
                        phase = Phase.SPACE;
                    }
                }
                case VALUE -> {
                    int close = line.indexOf(currentAttr.quote, i);
                    if (close < 0) {
                        currentAttr.value.append(line, i, n);
                        return n;
                    }
                    currentAttr.value.append(line, i, close);
                    currentAttr.valueEnd = close;
                    attrs.add(currentAttr);
                    currentAttr = null;
                    phase = Phase.SPACE;
                    i = close + 1;
                }
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

    private void finishTag(boolean selfClosing) {
        mode = Mode.TEXT;
        String tagName = name.toString();
        if (endTag) {
            handleEndTag(tagName);
        } else {
            handleStartTag(tagName, selfClosing);
        }
    }

    private void handleEndTag(String tagName) {
        if (stack.size() <= 1) {
            return;
        }
        Frame top = stack.peek();
        if (top.emitNames()) {
            emitName(OccurrenceKind.ELEMENT_NAME, tagName, nameLine, nameStart, nameEnd, top.namespaces(), true);
        }
        stack.pop();
    }

    private void handleStartTag(String tagName, boolean selfClosing) {
        Frame parent = stack.peek();
        Map<String, String> namespaces = parent.namespaces();
        String base = parent.base();
        boolean copied = false;
        for (Attr attr : attrs) {
            if (attr.name.equals("xmlns") || attr.name.startsWith("xmlns:")) {
                if (!copied) {
                    namespaces = new HashMap<>(namespaces);
                    copied = true;
                }
                String decoded = decode(attr.value.toString(), entities);
                namespaces.put(attr.name.equals("xmlns") ? "" : attr.name.substring(6), decoded == null ? "" : decoded);
            }
        }
        for (Attr attr : attrs) {
            if (attr.name.equals("xml:base")) {
                String decoded = decode(attr.value.toString(), entities);
                String resolved = decoded == null ? null : TurtleLineScanner.resolveAgainst(parent.base(), decoded);
                base = resolved;
            }
        }
        boolean isRoot = !rootSeen;
        if (isRoot) {
            rootSeen = true;
            rootNamespaces = namespaces;
            rootBase = base;
        }
        String elementIri = expandQName(tagName, namespaces, true);
        if (parent.childRole() == Role.LITERAL) {
            if (!selfClosing) {
                stack.push(new Frame(namespaces, base, Role.LITERAL, null, false));
            }
            return;
        }
        if (isRoot && (RDF_NS + "RDF").equals(elementIri)) {
            if (!selfClosing) {
                stack.push(new Frame(namespaces, base, Role.NODE, null, false));
            }
            return;
        }
        emitName(OccurrenceKind.ELEMENT_NAME, tagName, nameLine, nameStart, nameEnd, namespaces, true);

        Role role = parent.childRole();
        String aboutIri = null;
        String resourceIri = null;
        String parseType = null;
        List<String> typeValues = new ArrayList<>();
        for (Attr attr : attrs) {
            if (attr.name.equals("xmlns") || attr.name.startsWith("xmlns:") || attr.name.startsWith("xml:")) {
                continue;
            }
            String attrIri = attr.name.indexOf(':') < 0
                    ? (SYNTAX_ATTRIBUTES.contains(attr.name) ? RDF_NS + attr.name : null)
                    : expandQName(attr.name, namespaces, false);
            if (attrIri == null) {
                continue;
            }
            String decoded = decode(attr.value.toString(), entities);
            switch (attrIri.startsWith(RDF_NS) ? attrIri.substring(RDF_NS.length()) : "") {
                case "about" -> aboutIri = emitValue(OccurrenceKind.ABOUT, attr, decoded, base, namespaces);
                case "resource" -> {
                    resourceIri = emitValue(OccurrenceKind.RESOURCE, attr, decoded, base, namespaces);
                    reference(resourceIri);
                }
                case "datatype" -> reference(emitValue(OccurrenceKind.DATATYPE, attr, decoded, base, namespaces));
                case "ID" -> {
                    String idIri = decoded == null ? null : TurtleLineScanner.resolveAgainst(base, "#" + decoded);
                    emit(new Occurrence(attr.valueLine, attr.valueStart, attr.valueEnd, OccurrenceKind.ID, idIri,
                            attr.value.toString(), attr.quote, namespaces, base, idIri != null, attr.multiLine));
                    if (role == Role.NODE) {
                        aboutIri = idIri;
                    }
                }
                case "nodeID", "bagID", "aboutEach", "aboutEachPrefix" -> {
                }
                case "parseType" -> parseType = decoded;
                default -> {
                    emitName(OccurrenceKind.ATTRIBUTE_NAME, attr.name, attr.nameLine, attr.nameStart, attr.nameEnd,
                            namespaces, false);
                    reference(attrIri);
                    if ((RDF_NS + "type").equals(attrIri)) {
                        String typeIri = emitValue(OccurrenceKind.TYPE_VALUE, attr, decoded, base, namespaces);
                        reference(typeIri);
                        if (typeIri != null) {
                            typeValues.add(typeIri);
                        }
                    }
                }
            }
        }

        Frame frame;
        if (role == Role.NODE) {
            if (aboutIri != null) {
                listener.subject(aboutIri);
            }
            if (elementIri != null && !(RDF_NS + "Description").equals(elementIri)) {
                reference(elementIri);
                if (aboutIri != null) {
                    listener.declared(aboutIri, elementIri);
                }
            }
            if (aboutIri != null) {
                for (String type : typeValues) {
                    listener.declared(aboutIri, type);
                }
            }
            frame = new Frame(namespaces, base, Role.PROPERTY, aboutIri, true);
        } else {
            reference(elementIri);
            if ((RDF_NS + "type").equals(elementIri) && resourceIri != null && parent.subject() != null) {
                listener.declared(parent.subject(), resourceIri);
            }
            Role childRole = "Resource".equals(parseType) ? Role.PROPERTY
                    : "Literal".equals(parseType) ? Role.LITERAL : Role.NODE;
            frame = new Frame(namespaces, base, childRole, null, true);
        }
        if (!selfClosing) {
            stack.push(frame);
        }
    }

    private void reference(String iri) {
        if (iri != null) {
            listener.reference(iri);
        }
    }

    private String emitValue(OccurrenceKind kind, Attr attr, String decoded, String base, Map<String, String> namespaces) {
        String iri = decoded == null ? null : TurtleLineScanner.resolveAgainst(base, decoded);
        emit(new Occurrence(attr.valueLine, attr.valueStart, attr.valueEnd, kind, iri, attr.value.toString(),
                attr.quote, namespaces, base, iri != null, attr.multiLine));
        return iri;
    }

    private void emitName(OccurrenceKind kind, String qname, long line, int start, int end,
                          Map<String, String> namespaces, boolean element) {
        String iri = expandQName(qname, namespaces, element);
        emit(new Occurrence(line, start, end, kind, iri, qname, (char) 0, namespaces, null, iri != null, false));
    }

    private void emit(Occurrence occurrence) {
        listener.occurrence(occurrence);
    }

    public static String expandQName(String qname, Map<String, String> namespaces, boolean element) {
        int colon = qname.indexOf(':');
        if (colon < 0) {
            if (!element) {
                return null;
            }
            String defaultNs = namespaces.get("");
            return defaultNs == null || defaultNs.isEmpty() ? null : defaultNs + qname;
        }
        String ns = namespaces.get(qname.substring(0, colon));
        return ns == null ? null : ns + qname.substring(colon + 1);
    }

    public static String decode(String raw, Map<String, String> entityValues) {
        if (raw.indexOf('&') < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c != '&') {
                sb.append(c);
                i++;
                continue;
            }
            int semi = raw.indexOf(';', i);
            if (semi < 0) {
                return null;
            }
            String ref = raw.substring(i + 1, semi);
            if (ref.startsWith("#x") || ref.startsWith("#X")) {
                try {
                    sb.appendCodePoint(Integer.parseInt(ref.substring(2), 16));
                } catch (IllegalArgumentException e) {
                    return null;
                }
            } else if (ref.startsWith("#")) {
                try {
                    sb.appendCodePoint(Integer.parseInt(ref.substring(1)));
                } catch (IllegalArgumentException e) {
                    return null;
                }
            } else {
                String value = entityValues.get(ref);
                if (value == null) {
                    return null;
                }
                sb.append(value);
            }
            i = semi + 1;
        }
        return sb.toString();
    }

    public static boolean isNcName(String value) {
        if (value.isEmpty()) {
            return false;
        }
        int first = value.codePointAt(0);
        if (!(Character.isLetter(first) || first == '_')) {
            return false;
        }
        int i = Character.charCount(first);
        while (i < value.length()) {
            int cp = value.codePointAt(i);
            if (!(Character.isLetterOrDigit(cp) || cp == '_' || cp == '-' || cp == '.' || cp == 0x00B7)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    public static String escapeAttribute(String value, char quote) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append(quote == '"' ? "&quot;" : "\"");
                case '\'' -> sb.append(quote == '\'' ? "&apos;" : "'");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
