package self.research.ontology.migrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import self.research.ontology.migrator.core.BlobCopier;
import self.research.ontology.migrator.core.BlobSink;
import self.research.ontology.migrator.core.BlobSource;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.DedupPlan;
import self.research.ontology.migrator.core.MigrationReport;
import self.research.ontology.migrator.core.MigrationRunner;
import self.research.ontology.migrator.core.Options;
import self.research.ontology.migrator.core.SourceReader;
import self.research.ontology.migrator.core.Target;
import self.research.ontology.migrator.core.TargetReader;
import self.research.ontology.migrator.core.Verification;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class Migrator {

    private static final Logger LOG = LoggerFactory.getLogger(Migrator.class);

    private final Options options;
    private final List<CollectionMigration> migrations;
    private final SourceReader source;
    private final BlobSource blobSource;
    private final Target target;
    private final TargetReader targetReader;
    private final BlobSink blobSink;
    private final Supplier<Map<String, Long>> orphanChecks;

    public Migrator(Options options, List<CollectionMigration> migrations, SourceReader source, BlobSource blobSource,
                    Target target, TargetReader targetReader, BlobSink blobSink, Supplier<Map<String, Long>> orphanChecks) {
        this.options = options;
        this.migrations = migrations;
        this.source = source;
        this.blobSource = blobSource;
        this.target = target;
        this.targetReader = targetReader;
        this.blobSink = blobSink;
        this.orphanChecks = orphanChecks;
    }

    public void run(MigrationReport report) {
        report.startedAt = Instant.now().toString();
        report.dryRun = options.dryRun;
        report.verifyOnly = options.verifyOnly;
        report.allowOrphans = options.allowOrphans;
        BlobCopier copier = new BlobCopier(blobSource, blobSink, options.dryRun);
        Map<String, DedupPlan> plans = new HashMap<>();
        boolean blobsSelected = options.selected("blobs");
        selectedMigrations().forEach(m -> m.prepare(source));
        if (!options.verifyOnly) {
            if (blobsSelected) {
                for (String bucket : options.buckets) {
                    LOG.info("[blobs] copying bucket {}", bucket);
                    report.blobs.put(bucket, copier.copy(bucket));
                }
            }
            MigrationRunner runner = new MigrationRunner(source, target, options.batchSize, options.dryRun, options.fresh);
            for (CollectionMigration m : selectedMigrations()) {
                LOG.info("[{}] migrating", m.name());
                DedupPlan plan = DedupPlan.build(source, m, options.batchSize);
                plans.put(m.name(), plan);
                report.collections.put(m.name(), runner.run(m, plan));
            }
        }
        if (options.dryRun) {
            report.notes.add("Dry run: nothing was written. Blob counts show what would be copied, file content was not read.");
        } else {
            Verification verification = new Verification(source, targetReader, options.batchSize, options.sampleSize);
            for (CollectionMigration m : selectedMigrations()) {
                LOG.info("[{}] verifying", m.name());
                DedupPlan plan = plans.computeIfAbsent(m.name(), k -> DedupPlan.build(source, m, options.batchSize));
                report.verification.put(m.name(), verification.verify(m, plan));
            }
            if (blobsSelected) {
                for (String bucket : options.buckets) {
                    report.blobVerification.put(bucket, copier.verify(bucket, options.sampleSize));
                }
            }
            if (orphanChecks != null) {
                report.orphans.putAll(orphanChecks.get());
            }
        }
        report.finishedAt = Instant.now().toString();
        report.computeReconciled();
    }

    private List<CollectionMigration> selectedMigrations() {
        return migrations.stream()
                .filter(m -> options.only.isEmpty() || options.only.contains(m.name()) || options.only.contains(m.sourceCollection()))
                .toList();
    }
}
