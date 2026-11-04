package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;
import self.research.ontology.common.preview.PreviewStamp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class MutationPreconditionGuard {

    private final OntologyVersionStamps stamps;

    public MutationPreconditionGuard(OntologyVersionStamps stamps) {
        this.stamps = stamps;
    }

    public void check(String projectId, boolean draft, String userId, PreviewStamp expected) {
        if (expected == null || expected.isEmpty()) {
            return;
        }
        List<String> reasons = new ArrayList<>();
        if (draft) {
            if (expected.draftVersion() != null
                    && !Objects.equals(expected.draftVersion(), stamps.draftVersion(projectId, userId))) {
                reasons.add("your draft changed after the preview was made");
            }
        } else if (expected.ontologyRevision() != null
                && expected.ontologyRevision() != stamps.publicRevision(projectId)) {
            reasons.add("the ontology changed after the preview was made");
        }
        if (expected.rulesFingerprint() != null
                && !Objects.equals(expected.rulesFingerprint(), stamps.rulesFingerprint(projectId))) {
            reasons.add("the saved rules changed after the preview was made");
        }
        if (!reasons.isEmpty()) {
            throw new StalePreviewException(reasons);
        }
    }
}
