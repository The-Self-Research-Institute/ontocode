package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.SWRLRule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

public final class UnannotatedRules {

    private UnannotatedRules() {
    }

    public static String hashId(SWRLRule rule) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(rule.getAxiomWithoutAnnotations().toString().getBytes(StandardCharsets.UTF_8));
            return "rule-" + HexFormat.of().formatHex(bytes, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String shape(SWRLRule rule) {
        SWRLRule bare = rule.getAxiomWithoutAnnotations();
        List<String> body = bare.body().map(Object::toString).map(UnannotatedRules::flatten).sorted().toList();
        List<String> head = bare.head().map(Object::toString).map(UnannotatedRules::flatten).sorted().toList();
        return String.join(",", body) + "->" + String.join(",", head);
    }

    private static String flatten(String atom) {
        return atom.replaceAll("Variable\\(<[^>]*>\\)", "?v").replaceAll("\\s+", " ");
    }
}
