package self.research.ontology.owlEditor.service.delta;

import self.research.ontology.owlEditor.service.Limit;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CanonicalGraph {

    public static final String OWNED_NAMESPACE = "http://ontocode.org/haskey/";

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private CanonicalGraph() {
    }

    public static boolean isComplete(Model model) {
        Set<Resource> described = new HashSet<>();
        for (Statement statement : model) {
            described.add(statement.getSubject());
        }
        for (Statement statement : model) {
            Value object = statement.getObject();
            boolean structural = object instanceof BNode
                    || (object instanceof IRI iri && iri.stringValue().startsWith(OWNED_NAMESPACE));
            if (structural && !described.contains(object)) {
                return false;
            }
        }
        return true;
    }

    public static Model canonicalise(Model model) {
        Map<BNode, List<Statement>> incident = new HashMap<>();
        for (Statement statement : model) {
            if (statement.getSubject() instanceof BNode b) {
                incident.computeIfAbsent(b, key -> new ArrayList<>()).add(statement);
            }
            if (statement.getObject() instanceof BNode b) {
                incident.computeIfAbsent(b, key -> new ArrayList<>()).add(statement);
            }
        }
        Map<BNode, String> colours = distinguishDuplicates(colour(incident));
        Model result = new LinkedHashModel();
        for (Statement statement : model) {
            result.add(relabel(statement.getSubject(), colours), statement.getPredicate(),
                    relabel(statement.getObject(), colours));
        }
        return result;
    }

    private static Map<BNode, String> distinguishDuplicates(Map<BNode, String> colours) {
        Map<String, List<BNode>> byColour = new HashMap<>();
        for (Map.Entry<BNode, String> entry : colours.entrySet()) {
            byColour.computeIfAbsent(entry.getValue(), key -> new ArrayList<>()).add(entry.getKey());
        }
        Map<BNode, String> distinct = new HashMap<>();
        for (Map.Entry<String, List<BNode>> group : byColour.entrySet()) {
            List<BNode> members = group.getValue();
            members.sort(java.util.Comparator.comparing(BNode::getID));
            for (int i = 0; i < members.size(); i++) {
                distinct.put(members.get(i), i == 0 ? group.getKey() : group.getKey() + "x" + i);
            }
        }
        return distinct;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Value> T relabel(T value, Map<BNode, String> colours) {
        if (value instanceof BNode bnode) {
            return (T) VF.createBNode("c" + colours.get(bnode));
        }
        return value;
    }

    private static Map<BNode, String> colour(Map<BNode, List<Statement>> incident) {
        Map<BNode, String> colours = new HashMap<>();
        for (BNode bnode : incident.keySet()) {
            colours.put(bnode, "0");
        }
        for (int round = 0; round < Limit.CANONICAL_ROUNDS.value(); round++) {
            Map<BNode, String> next = new HashMap<>();
            for (Map.Entry<BNode, List<Statement>> entry : incident.entrySet()) {
                next.put(entry.getKey(), hash(signature(entry.getKey(), entry.getValue(), colours)));
            }
            colours = next;
        }
        return colours;
    }

    private static String signature(BNode self, List<Statement> statements, Map<BNode, String> colours) {
        List<String> edges = new ArrayList<>();
        for (Statement statement : statements) {
            if (self.equals(statement.getSubject())) {
                edges.add("o|" + statement.getPredicate() + "|" + key(statement.getObject(), self, colours));
            }
            if (self.equals(statement.getObject())) {
                edges.add("i|" + statement.getPredicate() + "|" + key(statement.getSubject(), self, colours));
            }
        }
        Collections.sort(edges);
        return String.join("\n", edges);
    }

    private static String key(Value value, BNode self, Map<BNode, String> colours) {
        if (value instanceof BNode other) {
            return self.equals(other) ? "*" : "_" + colours.get(other);
        }
        return value.toString();
    }

    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
