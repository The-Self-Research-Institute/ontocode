package self.research.ontology.common.preview;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PreviewStamp(Long ontologyRevision, String draftVersion, String rulesFingerprint) {

    @JsonIgnore
    public boolean isEmpty() {
        return ontologyRevision == null && draftVersion == null && rulesFingerprint == null;
    }
}
