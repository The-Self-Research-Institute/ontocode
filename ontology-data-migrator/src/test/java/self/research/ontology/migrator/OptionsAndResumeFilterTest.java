package self.research.ontology.migrator;

import org.bson.BsonType;
import org.junit.jupiter.api.Test;
import self.research.ontology.migrator.core.MigrationReport;
import self.research.ontology.migrator.core.Options;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionsAndResumeFilterTest {

    @Test
    void secretsComeFromTheEnvironmentByDefault() {
        Options o = Options.parse(new String[]{"--pg-url", "jdbc:postgresql://h/db", "--blob-root", "/b"},
                Map.of("ONTOCODE_MONGO_URI", "mongodb://secret", "ONTOCODE_DB_PASSWORD", "pw"));
        assertEquals("mongodb://secret", o.mongoUri);
        assertEquals("pw", o.pgPassword);
        assertEquals(1000, o.batchSize);
        assertEquals(200, o.sampleSize);
        assertEquals(List.of("fs", "plugins", "desktop-installers"), o.buckets);
        o.validate();
    }

    @Test
    void allFlagsAreParsed() {
        Options o = Options.parse(new String[]{"--mongo-db", "d", "--batch-size", "50", "--only", "users, workspaces", "--dry-run",
                "--sample-size", "10", "--fresh", "--allow-orphans", "--report-dir", "out", "--pg-user", "me", "--buckets", "fs"}, Map.of("ONTOCODE_MONGO_URI", "x"));
        assertEquals("d", o.mongoDb);
        assertEquals(50, o.batchSize);
        assertEquals(10, o.sampleSize);
        assertTrue(o.dryRun && o.fresh && o.allowOrphans);
        assertEquals(List.of("users", "workspaces"), List.copyOf(o.only));
        assertTrue(o.selected("users"));
        assertFalse(o.selected("plugins"));
        assertEquals(List.of("fs"), o.buckets);
        o.validate();
    }

    @Test
    void badInputIsRejectedWithAClearMessage() {
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[]{"--nope"}, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[]{"--batch-size"}, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[]{"--batch-size", "0"}, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[]{"--pg-url", "x", "--blob-root", "b"}, Map.of()).validate());
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[]{"--dry-run", "--verify-only"}, Map.of("ONTOCODE_MONGO_URI", "x")).validate());
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[]{}, Map.of("ONTOCODE_MONGO_URI", "x")).validate());
    }

    @Test
    void theResumeFilterAlsoCoversIdTypesThatSortAfterTheLastOne() {
        assertEquals(List.of(BsonType.DOCUMENT, BsonType.BINARY, BsonType.OBJECT_ID, BsonType.BOOLEAN, BsonType.DATE_TIME),
                self.research.ontology.migrator.mongo.ResumeFilter.typesAfter("some-string-id"));
        assertEquals(List.of(BsonType.BOOLEAN, BsonType.DATE_TIME),
                self.research.ontology.migrator.mongo.ResumeFilter.typesAfter(new org.bson.types.ObjectId()));
        assertTrue(self.research.ontology.migrator.mongo.ResumeFilter.typesAfter(5).contains(BsonType.STRING));
    }

    @Test
    void reportExitSemantics() {
        MigrationReport report = new MigrationReport();
        MigrationReport.CollectionStats ok = new MigrationReport.CollectionStats("a", "a");
        ok.read = 3;
        ok.written = 2;
        ok.dropped = 1;
        report.collections.put("a", ok);
        assertTrue(report.computeReconciled());
        ok.read = 4;
        assertFalse(report.computeReconciled());
        ok.read = 3;
        ok.fail("x");
        assertFalse(report.computeReconciled());
        assertTrue(report.toText().contains("NOT RECONCILED"));
    }
}
