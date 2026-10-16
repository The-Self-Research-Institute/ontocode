package self.research.ontology.owlEditor.service.delta;

import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ExactSeeds {

    private static final Set<String> ENTITY_LEVEL = Set.of(
            "createClass", "deleteClass",
            "createObjectProperty", "deleteObjectProperty",
            "createDataProperty", "deleteDataProperty",
            "createAnnotationProperty", "deleteAnnotationProperty",
            "createDatatype", "deleteDatatype",
            "createIndividual", "deleteIndividual");

    private ExactSeeds() {
    }

    public static boolean isEntityLevel(String type) {
        return type != null && ENTITY_LEVEL.contains(type);
    }

    public static List<MutationOp> eligible(List<MutationOp> ops) {
        List<MutationOp> eligible = new ArrayList<>();
        for (MutationOp op : ops) {
            if (op.iri() != null && !op.iri().isBlank()) {
                eligible.add(op);
            }
        }
        return eligible;
    }

    public static boolean needsWholeGraph(List<MutationOp> ops) {
        for (MutationOp op : ops) {
            if (isEntityLevel(op.type()) && op.type().startsWith("delete") || "renameEntity".equals(op.type())) {
                return true;
            }
        }
        return false;
    }

    public static Set<String> seeds(List<MutationOp> ops) {
        Set<String> seeds = new LinkedHashSet<>();
        for (MutationOp op : ops) {
            addIfIri(seeds, op.iri());
            addIfIri(seeds, op.target());
            addIfIri(seeds, op.classIri());
            addIfIri(seeds, op.ancestorIri());
            addIfIri(seeds, op.value());
            addIfIri(seeds, op.oldValue());
            addIfIri(seeds, negativeAssertionIri(op));
        }
        return seeds;
    }

    public static Set<String> incomingSeeds(List<MutationOp> ops) {
        Set<String> seeds = new LinkedHashSet<>();
        for (MutationOp op : ops) {
            if (op.type() != null && (op.type().startsWith("addGCA") || "deleteAxiom".equals(op.type()))) {
                addIfIri(seeds, op.iri());
            }
        }
        return seeds;
    }

    static String negativeAssertionIri(MutationOp op) {
        String type = op.type();
        if (type == null || !type.contains("NegativeObjectPropertyAssertion") && !type.contains("NegativeDataPropertyAssertion")) {
            return null;
        }
        String last = type.contains("NegativeObjectPropertyAssertion") ? op.target() : op.value();
        return "http://ontocode.org/axiom/negativePropertyAssertion/" + sha256Hex(op.iri() + "|" + op.property() + "|" + last);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Set<String> groupIris(List<MutationOp> ops) {
        Set<String> iris = new LinkedHashSet<>();
        for (MutationOp op : ops) {
            if (op.iri() != null) {
                iris.add(op.iri());
            }
        }
        return iris;
    }

    private static void addIfIri(Set<String> seeds, String candidate) {
        if (candidate != null && (candidate.startsWith("http://") || candidate.startsWith("https://")
                || candidate.startsWith("urn:"))) {
            seeds.add(candidate);
        }
    }
}
