package self.research.ontology.migrator;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.common.db.DiskBlobStore;
import self.research.ontology.common.db.StoredFile;
import self.research.ontology.common.db.StoredFiles;
import self.research.ontology.migrator.core.BlobCopier;
import self.research.ontology.migrator.core.BlobFile;
import self.research.ontology.migrator.core.BlobSink;
import self.research.ontology.migrator.core.MigrationReport;
import self.research.ontology.migrator.persistence.DiskBlobSink;
import self.research.ontology.migrator.support.MemoryBlobSource;
import self.research.ontology.migrator.support.TestDb;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlobMigrationTest {

    private static final String ID_A = "65a1b2c3d4e5f60718293a4b";
    private static final String ID_B = "65a1b2c3d4e5f60718293a4c";

    @TempDir
    Path root;

    private JdbcTemplate jdbc;
    private DiskBlobStore store;
    private DiskBlobSink sink;

    @BeforeEach
    void setUp() {
        jdbc = TestDb.clean();
        store = new DiskBlobStore(new StoredFiles(jdbc, new com.fasterxml.jackson.databind.ObjectMapper()), root, 0);
        sink = new DiskBlobSink(store);
    }

    private static BlobFile file(String bucket, String id, long length) {
        return new BlobFile(bucket, id, id, "name-" + id + ".owl", length, "application/rdf+xml",
                Instant.parse("2026-01-05T10:00:00Z"), Map.of("projectId", "proj-1", "origin", "gridfs"));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void copiesContentUnderTheSameIdWithItsMetadata() throws Exception {
        byte[] a = "<rdf>alpha</rdf>".getBytes(StandardCharsets.UTF_8);
        byte[] b = new byte[3 * 1024 * 1024 + 17];
        new java.util.Random(7).nextBytes(b);
        MemoryBlobSource source = new MemoryBlobSource().add(file("fs", ID_A, a.length), a).add(file("fs", ID_B, b.length), b);
        MigrationReport.BlobStats stats = new BlobCopier(source, sink, false).copy("fs");
        assertEquals(2, stats.files);
        assertEquals(2, stats.copied);
        assertEquals(a.length + (long) b.length, stats.bytes);
        assertTrue(stats.reconciled());

        StoredFile stored = store.info(ID_A).orElseThrow();
        assertEquals("fs", stored.bucket());
        assertEquals("name-" + ID_A + ".owl", stored.filename());
        assertEquals("application/rdf+xml", stored.contentType());
        assertEquals(Instant.parse("2026-01-05T10:00:00Z"), stored.uploadDate());
        assertEquals("proj-1", stored.metadata().get("projectId"));
        assertEquals(a.length, stored.length());
        assertEquals(sha256(a), stored.sha256());
        assertEquals(sha256(b), store.info(ID_B).orElseThrow().sha256());
        try (InputStream in = store.open(ID_B)) {
            assertEquals(sha256(b), BlobCopier.sha256(in));
        }
    }

    @Test
    void filesAlreadyCopiedAreSkippedOnARerun() {
        byte[] a = "one".getBytes(StandardCharsets.UTF_8);
        int[] opened = {0};
        MemoryBlobSource source = new MemoryBlobSource().add(file("fs", ID_A, a.length), f -> {
            opened[0]++;
            return new ByteArrayInputStream(a);
        });
        new BlobCopier(source, sink, false).copy("fs");
        MigrationReport.BlobStats again = new BlobCopier(source, sink, false).copy("fs");
        assertEquals(1, again.alreadyPresent);
        assertEquals(0, again.copied);
        assertEquals(1, opened[0]);
        assertEquals(1L, jdbc.queryForObject("select count(*) from stored_file", Long.class));
    }

    @Test
    void aMissingFileOnDiskIsCopiedAgain() throws IOException {
        byte[] a = "one".getBytes(StandardCharsets.UTF_8);
        MemoryBlobSource source = new MemoryBlobSource().add(file("fs", ID_A, a.length), a);
        new BlobCopier(source, sink, false).copy("fs");
        Files.delete(store.contentPath("fs", ID_A));
        MigrationReport.BlobStats again = new BlobCopier(source, sink, false).copy("fs");
        assertEquals(1, again.copied);
        assertTrue(Files.isRegularFile(store.contentPath("fs", ID_A)));
    }

    @Test
    void aLengthMismatchIsReportedAndNothingIsKept() {
        byte[] a = "four".getBytes(StandardCharsets.UTF_8);
        MemoryBlobSource source = new MemoryBlobSource().add(file("fs", ID_A, 999), a);
        MigrationReport.BlobStats stats = new BlobCopier(source, sink, false).copy("fs");
        assertEquals(1, stats.sha256Mismatches);
        assertEquals(1, stats.failed);
        assertFalse(stats.reconciled());
        assertTrue(store.info(ID_A).isEmpty());
        assertFalse(Files.exists(store.contentPath("fs", ID_A)));
    }

    @Test
    void anIdThatCannotBeAFileNameIsReportedNotFatal() {
        byte[] a = "x".getBytes(StandardCharsets.UTF_8);
        MemoryBlobSource source = new MemoryBlobSource().add(file("fs", "../evil", 1), a).add(file("fs", ID_B, 1), a);
        MigrationReport.BlobStats stats = new BlobCopier(source, sink, false).copy("fs");
        assertEquals(1, stats.failed);
        assertEquals(1, stats.copied);
    }

    @Test
    void dryRunReadsNoContentAndWritesNothing() {
        int[] opened = {0};
        MemoryBlobSource source = new MemoryBlobSource().add(file("fs", ID_A, 5), f -> {
            opened[0]++;
            return new ByteArrayInputStream(new byte[5]);
        });
        MigrationReport.BlobStats stats = new BlobCopier(source, sink, true).copy("fs");
        assertEquals(1, stats.files);
        assertEquals(5, stats.bytes);
        assertEquals(0, opened[0]);
        assertEquals(0L, jdbc.queryForObject("select count(*) from stored_file", Long.class));
    }

    @Test
    void verificationRehashesASampleFromTheSource() {
        byte[] a = "content".getBytes(StandardCharsets.UTF_8);
        MemoryBlobSource good = new MemoryBlobSource().add(file("fs", ID_A, a.length), a);
        new BlobCopier(good, sink, false).copy("fs");
        MigrationReport.VerifyStats ok = new BlobCopier(good, sink, false).verify("fs", 10);
        assertEquals(1, ok.sampled);
        assertTrue(ok.reconciled());

        byte[] changed = "CONTENT".getBytes(StandardCharsets.UTF_8);
        MemoryBlobSource drifted = new MemoryBlobSource().add(file("fs", ID_A, a.length), changed)
                .add(file("fs", ID_B, 3), new byte[3]);
        MigrationReport.VerifyStats bad = new BlobCopier(drifted, sink, false).verify("fs", 10);
        assertEquals(1, bad.fieldMismatches);
        assertEquals(1, bad.keysMissing);
        assertFalse(bad.reconciled());
    }

    @Test
    void draftBaselinesAreCopiedUnchangedWithTheirNameAndMetadata() throws Exception {
        byte[] rdf = "<rdf:RDF/>".getBytes(StandardCharsets.UTF_8);
        BlobFile baseline = new BlobFile("fs", ID_A, ID_A, "draft-baseline/proj-1/user-9", rdf.length, "application/rdf+xml",
                Instant.parse("2026-02-01T00:00:00Z"), Map.of("type", "draft-baseline", "projectId", "proj-1", "userId", "user-9"));
        MemoryBlobSource source = new MemoryBlobSource().add(baseline, rdf);
        assertEquals(1, new BlobCopier(source, sink, false).copy("fs").copied);
        StoredFile stored = store.info(ID_A).orElseThrow();
        assertEquals("draft-baseline/proj-1/user-9", stored.filename());
        assertEquals("draft-baseline", stored.metadata().get("type"));
        assertEquals("user-9", stored.metadata().get("userId"));
        assertEquals(sha256(rdf), stored.sha256());
        assertEquals(1, store.find("fs", "draft-baseline/proj-1/user-9", Map.of("type", "draft-baseline")).size());
    }

    @Test
    void bucketsThatDoNotExistAreSimplyEmpty() {
        MigrationReport.BlobStats stats = new BlobCopier(new MemoryBlobSource(), sink, false).copy("desktop-installers");
        assertEquals(0, stats.files);
        assertTrue(stats.reconciled());
    }

    @Test
    void filesDocumentsAreReadWithMetadataKeysDecoded() {
        Document d = new Document("_id", new org.bson.types.ObjectId(ID_A)).append("filename", "x.vsix").append("length", 12L)
                .append("uploadDate", java.util.Date.from(Instant.parse("2026-01-05T10:00:00Z")))
                .append("metadata", new Document("_contentType", "application/zip").append("a．b", "c"));
        BlobFile f = BlobFile.fromFilesDocument("plugins", d);
        assertEquals(ID_A, f.id());
        assertEquals("application/zip", f.contentType());
        assertEquals("c", f.metadata().get("a.b"));
        assertEquals(12L, f.length());
        Optional<BlobSink.Stored> none = sink.lookup(ID_A);
        assertTrue(none.isEmpty());
    }
}
