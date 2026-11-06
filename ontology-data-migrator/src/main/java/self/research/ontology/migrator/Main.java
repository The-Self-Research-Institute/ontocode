package self.research.ontology.migrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import self.research.ontology.migrator.collections.Migrations;
import self.research.ontology.migrator.core.MigrationReport;
import self.research.ontology.migrator.core.Options;
import self.research.ontology.migrator.mongo.MongoBlobSource;
import self.research.ontology.migrator.mongo.MongoSourceReader;
import self.research.ontology.migrator.persistence.Database;
import self.research.ontology.migrator.persistence.DiskBlobSink;
import self.research.ontology.migrator.persistence.JdbcTarget;
import self.research.ontology.migrator.persistence.JdbcTargetReader;
import self.research.ontology.migrator.persistence.OrphanChecks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Options options;
        try {
            options = Options.parse(args, System.getenv());
            options.validate();
        } catch (IllegalArgumentException e) {
            System.err.println("Usage problem: " + e.getMessage());
            return 2;
        }
        MigrationReport report = new MigrationReport();
        int exit;
        try (MongoSourceReader mongo = new MongoSourceReader(options.mongoUri, options.mongoDb)) {
            if (options.dryRun) {
                new Migrator(options, Migrations.all(), mongo, new MongoBlobSource(mongo), null, null,
                        new DiskBlobSink(null), null).run(report);
            } else {
                try (Database db = new Database(options)) {
                    db.migrateSchema();
                    new Migrator(options, Migrations.all(), mongo, new MongoBlobSource(mongo),
                            new JdbcTarget(db.dataSource()), new JdbcTargetReader(db.dataSource()),
                            new DiskBlobSink(db.blobStore(options.blobRoot)), new OrphanChecks(db.dataSource())::run).run(report);
                }
            }
            exit = report.reconciled ? 0 : 1;
        } catch (RuntimeException e) {
            LOG.error("Migration stopped: {}", e.getMessage());
            report.notes.add("Run stopped early: " + e.getMessage());
            report.computeReconciled();
            report.reconciled = false;
            exit = 2;
        }
        writeReport(report, options.reportDir);
        System.out.println(report.toText());
        return exit;
    }

    private static void writeReport(MigrationReport report, String dir) {
        try {
            Path folder = Path.of(dir);
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("migration-report.json"), report.toJson());
            Files.writeString(folder.resolve("migration-report.txt"), report.toText());
        } catch (IOException e) {
            System.err.println("Could not write the report files: " + e.getMessage());
        }
    }
}
