package self.research.ontology.owlEditor.service.infer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record InferenceSnapshotKey(String projectId, String scope, String ownerId, String ontologyRevision,
                                   String importsIdentity, String rulesFingerprint, String engineVersions,
                                   String reasoningConfig, String profileVersion, String limits) {

    public static final String PUBLIC = "public";
    public static final String DRAFT = "draft";

    public static InferenceSnapshotKey of(String projectId, boolean draft, String ownerId, String revision,
                                          String importsIdentity, String rulesFingerprint, String ruleEngine) {
        return new InferenceSnapshotKey(projectId, draft ? DRAFT : PUBLIC, draft ? ownerId : "", revision,
                importsIdentity, rulesFingerprint == null ? "" : rulesFingerprint,
                InferenceProfile.REASONER_VERSION + "|" + ruleEngine, InferenceProfile.REASONING_CONFIG,
                InferenceProfile.VERSION, InferenceProfile.limitsFingerprint());
    }

    public InferenceSnapshotKey withRulesFingerprint(String fingerprint) {
        return new InferenceSnapshotKey(projectId, scope, ownerId, ontologyRevision, importsIdentity, fingerprint,
                engineVersions, reasoningConfig, profileVersion, limits);
    }

    public InferenceSnapshotKey lookupKey() {
        return withRulesFingerprint("");
    }

    public String snapshotId() {
        String canonical = String.join("\u0001", projectId, scope, ownerId, ontologyRevision, importsIdentity,
                rulesFingerprint, engineVersions, reasoningConfig, profileVersion, limits);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
