package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.service.RdfXmlLexer.Attr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RdfXmlScanner {

    public static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    public static final String XML_NS = "http://www.w3.org/XML/1998/namespace";

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

    private enum Role { NODE, PROPERTY, LITERAL }

    private record Frame(Map<String, String> namespaces, String base, Role childRole, String subject,
                         boolean emitNames) {}

    private final Listener listener;
    private final Map<String, String> entities = new HashMap<>();
    private final Deque<Frame> stack = new ArrayDeque<>();
    private final RdfXmlLexer lexer;
    private boolean rootSeen;
    private Map<String, String> rootNamespaces;
    private String rootBase;

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
        lexer = new RdfXmlLexer(entities, this::finishTag);
    }

    public RdfXmlScanner fork(Listener forkListener) {
        Frame top = stack.peek();
        RdfXmlScanner copy = new RdfXmlScanner(top.namespaces(), top.base(), entities, forkListener);
        if (!atSafeBoundary()) {
            return copy;
        }
        copy.stack.clear();
        copy.stack.addAll(stack);
        copy.lexer.mode = lexer.mode;
        copy.rootSeen = rootSeen;
        copy.rootNamespaces = rootNamespaces;
        copy.rootBase = rootBase;
        copy.lexer.unsupportedReason = lexer.unsupportedReason;
        return copy;
    }

    public boolean atSafeBoundary() {
        return lexer.atSafeBoundary();
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
        return lexer.unsupportedReason;
    }

    public void feed(long lineNo, String line) {
        lexer.feed(lineNo, line);
    }

    private void finishTag(boolean endTag, String tagName, boolean selfClosing) {
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
            emitName(OccurrenceKind.ELEMENT_NAME, tagName, lexer.nameLine, lexer.nameStart, lexer.nameEnd,
                    top.namespaces(), true);
        }
        stack.pop();
    }

    private void handleStartTag(String tagName, boolean selfClosing) {
        Frame parent = stack.peek();
        Map<String, String> namespaces = scopedNamespaces(parent);
        String base = scopedBase(parent);
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
        emitName(OccurrenceKind.ELEMENT_NAME, tagName, lexer.nameLine, lexer.nameStart, lexer.nameEnd,
                namespaces, true);

        Role role = parent.childRole();
        AttrScan scan = scanAttributes(role, base, namespaces);

        Frame frame = role == Role.NODE
                ? nodeFrame(elementIri, scan, namespaces, base)
                : propertyFrame(elementIri, scan, parent, namespaces, base);
        if (!selfClosing) {
            stack.push(frame);
        }
    }

    private record AttrScan(String aboutIri, String resourceIri, String parseType, List<String> typeValues) {}

    private Map<String, String> scopedNamespaces(Frame parent) {
        Map<String, String> namespaces = parent.namespaces();
        boolean copied = false;
        for (Attr attr : lexer.attrs) {
            if (attr.name.equals("xmlns") || attr.name.startsWith("xmlns:")) {
                if (!copied) {
                    namespaces = new HashMap<>(namespaces);
                    copied = true;
                }
                String decoded = decode(attr.value.toString(), entities);
                namespaces.put(attr.name.equals("xmlns") ? "" : attr.name.substring(6), decoded == null ? "" : decoded);
            }
        }
        return namespaces;
    }

    private String scopedBase(Frame parent) {
        String base = parent.base();
        for (Attr attr : lexer.attrs) {
            if (attr.name.equals("xml:base")) {
                String decoded = decode(attr.value.toString(), entities);
                String resolved = decoded == null ? null : TurtleNames.resolveAgainst(parent.base(), decoded);
                base = resolved;
            }
        }
        return base;
    }

    private String attributeIri(Attr attr, Map<String, String> namespaces) {
        if (attr.name.equals("xmlns") || attr.name.startsWith("xmlns:") || attr.name.startsWith("xml:")) {
            return null;
        }
        return attr.name.indexOf(':') < 0
                ? (SYNTAX_ATTRIBUTES.contains(attr.name) ? RDF_NS + attr.name : null)
                : expandQName(attr.name, namespaces, false);
    }

    private AttrScan scanAttributes(Role role, String base, Map<String, String> namespaces) {
        String aboutIri = null;
        String resourceIri = null;
        String parseType = null;
        List<String> typeValues = new ArrayList<>();
        for (Attr attr : lexer.attrs) {
            String attrIri = attributeIri(attr, namespaces);
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
                    String idIri = decoded == null ? null : TurtleNames.resolveAgainst(base, "#" + decoded);
                    emit(new Occurrence(attr.valueLine, attr.valueStart, attr.valueEnd, OccurrenceKind.ID, idIri,
                            attr.value.toString(), attr.quote, namespaces, base, idIri != null, attr.multiLine));
                    if (role == Role.NODE) {
                        aboutIri = idIri;
                    }
                }
                case "nodeID", "bagID", "aboutEach", "aboutEachPrefix" -> {
                }
                case "parseType" -> parseType = decoded;
                default -> emitPropertyAttribute(attr, attrIri, decoded, base, namespaces, typeValues);
            }
        }
        return new AttrScan(aboutIri, resourceIri, parseType, typeValues);
    }

    private void emitPropertyAttribute(Attr attr, String attrIri, String decoded, String base,
                                       Map<String, String> namespaces, List<String> typeValues) {
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

    private Frame nodeFrame(String elementIri, AttrScan scan, Map<String, String> namespaces, String base) {
        String aboutIri = scan.aboutIri();
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
            for (String type : scan.typeValues()) {
                listener.declared(aboutIri, type);
            }
        }
        return new Frame(namespaces, base, Role.PROPERTY, aboutIri, true);
    }

    private Frame propertyFrame(String elementIri, AttrScan scan, Frame parent, Map<String, String> namespaces,
                                String base) {
        reference(elementIri);
        if ((RDF_NS + "type").equals(elementIri) && scan.resourceIri() != null && parent.subject() != null) {
            listener.declared(parent.subject(), scan.resourceIri());
        }
        String parseType = scan.parseType();
        Role childRole = "Resource".equals(parseType) ? Role.PROPERTY
                : "Literal".equals(parseType) ? Role.LITERAL : Role.NODE;
        return new Frame(namespaces, base, childRole, null, true);
    }

    private void reference(String iri) {
        if (iri != null) {
            listener.reference(iri);
        }
    }

    private String emitValue(OccurrenceKind kind, Attr attr, String decoded, String base, Map<String, String> namespaces) {
        String iri = decoded == null ? null : TurtleNames.resolveAgainst(base, decoded);
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
