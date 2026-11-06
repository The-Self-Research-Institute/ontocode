package self.research.ontology.migrator.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MigrationReport {

    public static final int MAX_EXAMPLES = 100;

    public static final class Dropped {
        public String id;
        public String rule;
        public String reason;

        public Dropped(String id, String rule, String reason) {
            this.id = id;
            this.rule = rule;
            this.reason = reason;
        }
    }

    public static final class CollectionStats {
        public String migration;
        public String sourceCollection;
        public boolean sourceMissing;
        public long sourceCount;
        public long read;
        public long written;
        public long rowsWritten;
        public long skippedOtherKind;
        public long dropped;
        public long failed;
        public Map<String, Long> droppedByRule = new LinkedHashMap<>();
        public List<Dropped> droppedExamples = new ArrayList<>();
        public List<String> failedExamples = new ArrayList<>();
        public Long cumulativeRead;
        public Long cumulativeWritten;
        public Long cumulativeSkipped;

        public CollectionStats(String migration, String sourceCollection) {
            this.migration = migration;
            this.sourceCollection = sourceCollection;
        }

        public void recordDropped(Dropped d) {
            dropped++;
            droppedByRule.merge(d.rule, 1L, Long::sum);
            if (droppedExamples.size() < MAX_EXAMPLES) {
                droppedExamples.add(d);
            }
        }

        public void fail(String message) {
            failed++;
            if (failedExamples.size() < MAX_EXAMPLES) {
                failedExamples.add(message);
            }
        }

        public boolean reconciled() {
            return failed == 0 && read == written + skippedOtherKind + dropped;
        }
    }

    public static final class BlobStats {
        public String bucket;
        public long files;
        public long bytes;
        public long copied;
        public long alreadyPresent;
        public long failed;
        public long sha256Mismatches;
        public List<String> problems = new ArrayList<>();

        public BlobStats(String bucket) {
            this.bucket = bucket;
        }

        public void problem(String message) {
            if (problems.size() < MAX_EXAMPLES) {
                problems.add(message);
            }
        }

        public boolean reconciled() {
            return failed == 0 && sha256Mismatches == 0 && files == copied + alreadyPresent + failed;
        }
    }

    public static final class VerifyStats {
        public String migration;
        public long keysChecked;
        public long keysMissing;
        public long sampled;
        public long fieldMismatches;
        public List<String> examples = new ArrayList<>();

        public VerifyStats(String migration) {
            this.migration = migration;
        }

        public void example(String message) {
            if (examples.size() < MAX_EXAMPLES) {
                examples.add(message);
            }
        }

        public boolean reconciled() {
            return keysMissing == 0 && fieldMismatches == 0;
        }
    }

    public String startedAt;
    public String finishedAt;
    public boolean dryRun;
    public boolean verifyOnly;
    public Map<String, CollectionStats> collections = new LinkedHashMap<>();
    public Map<String, BlobStats> blobs = new LinkedHashMap<>();
    public Map<String, VerifyStats> verification = new LinkedHashMap<>();
    public Map<String, VerifyStats> blobVerification = new LinkedHashMap<>();
    public Map<String, Long> orphans = new LinkedHashMap<>();
    public boolean allowOrphans;
    public List<String> notes = new ArrayList<>();
    public boolean reconciled;

    public boolean computeReconciled() {
        boolean ok = true;
        if (!verifyOnly) {
            ok &= collections.values().stream().allMatch(CollectionStats::reconciled);
            ok &= blobs.values().stream().allMatch(BlobStats::reconciled);
        }
        ok &= verification.values().stream().allMatch(VerifyStats::reconciled);
        ok &= blobVerification.values().stream().allMatch(VerifyStats::reconciled);
        if (!allowOrphans) {
            ok &= orphans.values().stream().allMatch(c -> c == 0);
        }
        reconciled = ok;
        return ok;
    }

    public String toJson() {
        try {
            return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Migration report").append(dryRun ? " (dry run, nothing written)" : "").append('\n');
        sb.append("Result: ").append(reconciled ? "RECONCILED" : "NOT RECONCILED").append("\n\n");
        sb.append(String.format("%-34s %9s %9s %9s %9s %9s%n", "collection", "read", "written", "other", "dropped", "failed"));
        for (CollectionStats s : collections.values()) {
            sb.append(String.format("%-34s %9d %9d %9d %9d %9d%s%n", s.migration, s.read, s.written, s.skippedOtherKind,
                    s.dropped, s.failed, s.sourceMissing ? "  (collection not found)" : ""));
            s.droppedByRule.forEach((rule, n) -> sb.append("    dropped ").append(n).append(" by ").append(rule).append('\n'));
            s.failedExamples.stream().limit(3).forEach(f -> sb.append("    failed: ").append(f).append('\n'));
        }
        for (BlobStats b : blobs.values()) {
            sb.append(String.format("blobs %-20s files %d, bytes %d, copied %d, already there %d, failed %d, hash mismatches %d%n",
                    b.bucket, b.files, b.bytes, b.copied, b.alreadyPresent, b.failed, b.sha256Mismatches));
            b.problems.stream().limit(3).forEach(p -> sb.append("    ").append(p).append('\n'));
        }
        for (VerifyStats v : verification.values()) {
            sb.append(String.format("verify %-27s keys checked %d, missing %d, sampled %d, field mismatches %d%n",
                    v.migration, v.keysChecked, v.keysMissing, v.sampled, v.fieldMismatches));
            v.examples.stream().limit(3).forEach(p -> sb.append("    ").append(p).append('\n'));
        }
        for (VerifyStats v : blobVerification.values()) {
            sb.append(String.format("verify blobs %-21s sampled %d, missing %d, mismatches %d%n",
                    v.migration, v.sampled, v.keysMissing, v.fieldMismatches));
            v.examples.stream().limit(3).forEach(p -> sb.append("    ").append(p).append('\n'));
        }
        orphans.forEach((name, n) -> {
            if (n > 0) {
                sb.append("orphans ").append(name).append(": ").append(n).append('\n');
            }
        });
        notes.forEach(n -> sb.append("note: ").append(n).append('\n'));
        return sb.toString();
    }
}
