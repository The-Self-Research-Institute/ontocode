package self.research.ontology.owlEditor.service.infer;

import java.util.LinkedHashMap;
import java.util.Map;

final class InferredMetadata {

    private InferredMetadata() {
    }

    static Map<String, Object> of(InferredSnapshot snapshot) {
        Materialization m = snapshot.materialization();
        Map<String, Object> equality = new LinkedHashMap<>();
        equality.put("groups", m.equality().groups());
        equality.put("largestGroup", m.equality().largestGroup());
        equality.put("triplesAdded", m.equality().triplesAdded());
        equality.put("incomplete", m.equality().incomplete());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("used", true);
        out.put("reasoner", InferenceProfile.REASONER);
        out.put("computedAt", snapshot.computedAt().toString());
        out.put("snapshotId", snapshot.key().snapshotId());
        out.put("scope", snapshot.key().scope());
        out.put("profile", InferenceProfile.VERSION);
        out.put("layers", InferenceProfile.LAYERS);
        out.put("assertedTripleCount", snapshot.assertedTriples());
        out.put("inferredTripleCount", snapshot.inferredTriples());
        out.put("partial", m.partial() || m.equality().incomplete());
        out.put("partialReasons", m.partialReasons());
        out.put("equality", equality);
        return out;
    }
}
