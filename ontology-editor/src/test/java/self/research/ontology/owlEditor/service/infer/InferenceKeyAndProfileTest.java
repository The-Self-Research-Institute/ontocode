package self.research.ontology.owlEditor.service.infer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceKeyAndProfileTest {

    static InferenceSnapshotKey key(String project, boolean draft, String owner, String revision, String rules) {
        return InferenceSnapshotKey.of(project, draft, owner, revision, "imports", rules, "SWRLAPI");
    }

    @Test
    void identicalInputsGiveTheSameKeyAndId() {
        InferenceSnapshotKey a = key("p", false, "ignored", "r1", "f1");
        InferenceSnapshotKey b = key("p", false, "other", "r1", "f1");
        assertEquals(a, b);
        assertEquals(a.snapshotId(), b.snapshotId());
    }

    @Test
    void everyIdentityInputChangesTheId() {
        String base = key("p", false, "", "r1", "f1").snapshotId();
        assertNotEquals(base, key("q", false, "", "r1", "f1").snapshotId());
        assertNotEquals(base, key("p", false, "", "r2", "f1").snapshotId());
        assertNotEquals(base, key("p", false, "", "r1", "f2").snapshotId());
        assertNotEquals(base, key("p", true, "u1", "r1", "f1").snapshotId());
        assertNotEquals(key("p", true, "u1", "r1", "f1").snapshotId(), key("p", true, "u2", "r1", "f1").snapshotId());
        assertNotEquals(base, InferenceSnapshotKey.of("p", false, "", "r1", "other-imports", "f1", "SWRLAPI").snapshotId());
        assertNotEquals(base, InferenceSnapshotKey.of("p", false, "", "r1", "imports", "f1", "other engine").snapshotId());
    }

    @Test
    void aNewRevisionIsANewKeyEvenWhenAnEarlierRevisionReturns() {
        InferenceSnapshotKey first = key("p", false, "", "r1", "f1");
        InferenceSnapshotKey afterEdit = key("p", false, "", "r2", "f1");
        InferenceSnapshotKey afterUndo = key("p", false, "", "r3", "f1");
        assertNotEquals(first, afterUndo);
        assertNotEquals(afterEdit, afterUndo);
    }

    @Test
    void lookupKeyIgnoresOnlyTheRulesFingerprint() {
        assertEquals(key("p", false, "", "r1", "f1").lookupKey(), key("p", false, "", "r1", "f2").lookupKey());
        assertNotEquals(key("p", false, "", "r1", "f1").lookupKey(), key("p", false, "", "r2", "f1").lookupKey());
    }

    @Test
    void theProfileIsDocumentedAndFixed() {
        assertEquals("v1", InferenceProfile.VERSION);
        assertEquals(List.of("owl", "equality", "rules"), InferenceProfile.LAYERS);
        assertEquals(10, InferenceProfile.MAX_GROUP_SIZE);
        assertEquals(100_000, InferenceProfile.MAX_EQUALITY_TRIPLES);
        assertEquals(200_000, InferenceProfile.MAX_ASSERTED_TRIPLES);
        assertEquals(60_000L, InferenceProfile.TIME_LIMIT_MS);
        assertEquals(2, InferenceProfile.CACHE_MAX_SNAPSHOTS);
        assertEquals(30L * 60L * 1000L, InferenceProfile.CACHE_TTL_MS);
        assertEquals(10, InferenceProfile.INCLUDED.size());
        assertTrue(InferenceProfile.INCLUDED.stream().anyMatch(s -> s.contains("owl:sameAs groups")));
        assertTrue(InferenceProfile.INCLUDED.stream().anyMatch(s -> s.contains("never from different IRIs")));
        assertTrue(InferenceProfile.INCLUDED.stream().anyMatch(s -> s.contains("every name of a sameAs group")));
        assertEquals(7, InferenceProfile.EXCLUDED.size());
        assertTrue(InferenceProfile.EXCLUDED.stream().anyMatch(s -> s.contains("anonymous")));
        assertTrue(InferenceProfile.EXCLUDED.stream().anyMatch(s -> s.contains("annotation")));
        assertTrue(InferenceProfile.EXCLUDED.stream().anyMatch(s -> s.contains("rule conclusions of sameAs")));
        assertTrue(InferenceProfile.INFERRED_NOTE.contains("not full OWL 2 DL"));
        assertEquals("HermiT 1.4.5.519", InferenceProfile.REASONER_VERSION);
    }
}
