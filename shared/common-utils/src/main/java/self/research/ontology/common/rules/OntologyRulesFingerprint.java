package self.research.ontology.common.rules;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLRule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class OntologyRulesFingerprint {

    public static final String NONE = "none";

    private OntologyRulesFingerprint() {
    }

    public static String of(OWLOntology ontology) {
        if (ontology == null) {
            return NONE;
        }
        List<String> rules = ontology.importsClosure()
                .flatMap(o -> o.axioms(AxiomType.SWRL_RULE))
                .filter(rule -> !DisabledRules.isExcluded(rule))
                .map(SWRLRule::getAxiomWithoutAnnotations)
                .map(Object::toString)
                .distinct()
                .sorted()
                .toList();
        if (rules.isEmpty()) {
            return NONE;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String rule : rules) {
                digest.update(rule.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 10);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
