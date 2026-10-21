package self.research.ontology.owlEditor.service.delta;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.model.vocabulary.XSD;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DeltaSparql {

    private static final int MAX_PAIRING = 2_000;

    private static final java.util.Set<IRI> NUMERIC = java.util.Set.of(XSD.DECIMAL, XSD.INTEGER, XSD.LONG, XSD.INT,
            XSD.SHORT, XSD.BYTE, XSD.NON_NEGATIVE_INTEGER, XSD.POSITIVE_INTEGER, XSD.NEGATIVE_INTEGER,
            XSD.NON_POSITIVE_INTEGER, XSD.UNSIGNED_LONG, XSD.UNSIGNED_INT, XSD.UNSIGNED_SHORT, XSD.UNSIGNED_BYTE);

    private static final String FORBIDDEN_IRI_CHARS = "<>\"{}|\\^`";

    private DeltaSparql() {
    }

    public static boolean isRenderable(Model model) {
        for (Statement statement : model) {
            if (!safeIri(statement.getPredicate().stringValue())
                    || (statement.getSubject() instanceof IRI s && !safeIri(s.stringValue()))
                    || (statement.getObject() instanceof IRI o && !safeIri(o.stringValue()))
                    || (statement.getObject() instanceof Literal l && l.getDatatype() != null
                            && !safeIri(l.getDatatype().stringValue()))) {
                return false;
            }
        }
        return true;
    }

    public static String reverse(ExactDelta delta) {
        return join(deleteStatements(delta.inserted()), insertData(delta.deleted()));
    }

    public static String forward(ExactDelta delta) {
        return join(deleteStatements(delta.deleted()), insertData(delta.inserted()));
    }

    public static List<String> holds(ExactDelta delta, boolean undo, String graphUri) {
        Model present = undo ? delta.inserted() : delta.deleted();
        Model absent = undo ? delta.deleted() : delta.inserted();
        List<String> queries = new ArrayList<>();
        if (!present.isEmpty()) {
            StringBuilder body = new StringBuilder(pattern(ground(present)));
            for (Model part : components(present).values()) {
                body.append(guardedPattern(part));
            }
            queries.add("ASK FROM <" + graphUri + "> WHERE { " + body + " }");
        }
        Model mustBeAbsent = new LinkedHashModel();
        for (Statement statement : ground(absent)) {
            if (!hasSameValueIn(present, statement)) {
                mustBeAbsent.add(statement);
            }
        }
        if (!mustBeAbsent.isEmpty()) {
            StringBuilder body = new StringBuilder();
            for (Statement statement : mustBeAbsent) {
                body.append("FILTER NOT EXISTS { ").append(triple(statement, true)).append(" } ");
            }
            queries.add("ASK FROM <" + graphUri + "> WHERE { " + body + "}");
        }
        return queries;
    }

    private static boolean hasSameValueIn(Model model, Statement statement) {
        if (!(statement.getObject() instanceof Literal literal) || literal.getLanguage().isPresent()) {
            return false;
        }
        for (Statement other : model.filter(statement.getSubject(), statement.getPredicate(), null)) {
            if (other.getObject() instanceof Literal candidate && sameNumber(literal, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameNumber(Literal a, Literal b) {
        if (a.getDatatype() == null || !a.getDatatype().equals(b.getDatatype()) || !NUMERIC.contains(a.getDatatype())) {
            return false;
        }
        try {
            return new java.math.BigDecimal(a.getLabel().trim()).compareTo(new java.math.BigDecimal(b.getLabel().trim())) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String join(List<String> deletes, String insert) {
        List<String> parts = new ArrayList<>(deletes);
        if (insert != null) {
            parts.add(insert);
        }
        return String.join(" ;\n", parts);
    }

    static List<String> deleteStatements(Model model) {
        List<String> statements = new ArrayList<>();
        Model ground = ground(model);
        if (!ground.isEmpty()) {
            StringBuilder body = new StringBuilder();
            for (Statement statement : ground) {
                body.append(triple(statement, true)).append(' ');
            }
            statements.add("DELETE DATA { " + body + "}");
        }
        for (Model part : components(model).values()) {
            statements.add("DELETE { " + pattern(part) + " } WHERE { " + guardedPattern(part) + " }");
        }
        return statements;
    }

    static String insertData(Model model) {
        if (model.isEmpty()) {
            return null;
        }
        StringBuilder body = new StringBuilder();
        boolean blank = false;
        for (Statement statement : model) {
            body.append(triple(statement, false)).append(' ');
            blank |= touchesBlankNode(statement);
        }
        return blank ? "INSERT { " + body + "} WHERE { }" : "INSERT DATA { " + body + "}";
    }

    private static String guardedPattern(Model component) {
        String body = pattern(component);
        boolean anchored = false;
        java.util.Set<String> subjects = new java.util.LinkedHashSet<>();
        java.util.Set<String> objects = new java.util.HashSet<>();
        for (Statement statement : component) {
            if (!(statement.getSubject() instanceof BNode)) {
                anchored = true;
            } else {
                subjects.add(label(statement.getSubject()));
            }
            if (statement.getObject() instanceof BNode object) {
                objects.add(object.getID());
            }
        }
        if (anchored) {
            return body;
        }
        StringBuilder guarded = new StringBuilder(body);
        int index = 0;
        for (String root : subjects) {
            if (!objects.contains(root)) {
                guarded.append("FILTER NOT EXISTS { ?g").append(index).append(" ?gp").append(index).append(" ?b")
                        .append(root.replaceAll("[^A-Za-z0-9]", "")).append(" } ");
                index++;
            }
        }
        return guarded.toString();
    }

    private static String pattern(Model model) {
        StringBuilder body = new StringBuilder();
        for (Statement statement : model) {
            body.append(triple(statement, true)).append(' ');
        }
        return body.toString();
    }

    private static Model ground(Model model) {
        Model ground = new LinkedHashModel();
        for (Statement statement : model) {
            if (!(statement.getSubject() instanceof BNode) && !(statement.getObject() instanceof BNode)) {
                ground.add(statement);
            }
        }
        return ground;
    }

    static ExactDelta withoutRelabelled(ExactDelta delta) {
        List<Model> insertedParts = new ArrayList<>(components(delta.inserted()).values());
        Map<String, Model> deletedParts = components(delta.deleted());
        if (insertedParts.isEmpty() || deletedParts.isEmpty()
                || insertedParts.size() > MAX_PAIRING || deletedParts.size() > MAX_PAIRING) {
            return delta;
        }
        Model deleted = new LinkedHashModel(delta.deleted());
        Model inserted = new LinkedHashModel(delta.inserted());
        for (Model part : deletedParts.values()) {
            for (java.util.Iterator<Model> candidates = insertedParts.iterator(); candidates.hasNext(); ) {
                Model candidate = candidates.next();
                if (candidate.size() == part.size() && Models.isomorphic(part, candidate)) {
                    deleted.removeAll(part);
                    inserted.removeAll(candidate);
                    candidates.remove();
                    break;
                }
            }
        }
        return new ExactDelta(deleted, inserted);
    }

    static boolean touchesBlankNode(Statement statement) {
        return statement.getSubject() instanceof BNode || statement.getObject() instanceof BNode;
    }

    static Map<String, Model> components(Model model) {
        Map<String, String> parent = new HashMap<>();
        for (Statement statement : model) {
            String a = label(statement.getSubject());
            String b = label(statement.getObject());
            if (a != null) {
                parent.putIfAbsent(a, a);
            }
            if (b != null) {
                parent.putIfAbsent(b, b);
            }
            if (a != null && b != null) {
                parent.put(find(parent, a), find(parent, b));
            }
        }
        Map<String, Model> parts = new LinkedHashMap<>();
        for (Statement statement : model) {
            String a = label(statement.getSubject());
            String b = label(statement.getObject());
            String anchor = a != null ? a : b;
            if (anchor == null) {
                continue;
            }
            parts.computeIfAbsent(find(parent, anchor), key -> new LinkedHashModel()).add(statement);
        }
        return parts;
    }

    private static String find(Map<String, String> parent, String node) {
        String root = node;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        return root;
    }

    private static String label(Value value) {
        return value instanceof BNode bnode ? bnode.getID() : null;
    }

    private static String triple(Statement statement, boolean asVariable) {
        return term(statement.getSubject(), asVariable) + " " + term(statement.getPredicate(), asVariable) + " "
                + term(statement.getObject(), asVariable) + " .";
    }

    private static String term(Value value, boolean asVariable) {
        if (value instanceof IRI iri) {
            return "<" + iri.stringValue() + ">";
        }
        if (value instanceof BNode bnode) {
            return (asVariable ? "?b" : "_:b") + bnode.getID().replaceAll("[^A-Za-z0-9]", "");
        }
        Literal literal = (Literal) value;
        String text = "\"" + escape(literal.getLabel()) + "\"";
        if (literal.getLanguage().isPresent()) {
            return text + "@" + literal.getLanguage().get();
        }
        if (literal.getDatatype() != null && !XSD.STRING.equals(literal.getDatatype())) {
            return text + "^^<" + literal.getDatatype().stringValue() + ">";
        }
        return text;
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '{', '}', ';' -> out.append(String.format("\\u%04X", (int) c));
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04X", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static boolean safeIri(String iri) {
        if (iri.isEmpty()) {
            return false;
        }
        for (char c : iri.toCharArray()) {
            if (c <= ' ' || FORBIDDEN_IRI_CHARS.indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }
}
