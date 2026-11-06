package self.research.ontology.common.db;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class BlobStoreContract {

    abstract BlobStore store();

    abstract JdbcTemplate jdbc();

    abstract String expectedStorage();

    static byte[] randomBytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    void smallFileRoundTripKeepsContentLengthChecksumMetadataAndStorageKind() throws Exception {
        byte[] data = "hello ontology".getBytes();
        StoredFile stored = store().store("fs", "a.owl", "application/rdf+xml", Map.of("projectId", "p1", "versionNumber", 3),
                new ByteArrayInputStream(data));

        assertEquals(data.length, stored.length());
        assertEquals(sha256(data), stored.sha256());
        assertEquals("p1", stored.metadata().get("projectId"));
        assertEquals(3, stored.metadata().get("versionNumber"));
        assertEquals("application/rdf+xml", stored.contentType());
        assertEquals(expectedStorage(), stored.storage());
        assertEquals(24, stored.id().length());
        try (InputStream in = store().open(stored.id())) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }

    @Test
    void anEmptyFileIsStoredAndReadBackEmpty() throws Exception {
        StoredFile stored = store().store("fs", "empty", null, Map.of(), new ByteArrayInputStream(new byte[0]));

        assertEquals(0, stored.length());
        try (InputStream in = store().open(stored.id())) {
            assertEquals(0, in.readAllBytes().length);
        }
    }

    @Test
    void filesAroundTheOneMegabyteBoundaryAreReassembledExactly() throws Exception {
        for (int size : new int[]{1024 * 1024 - 1, 1024 * 1024, 1024 * 1024 + 1, 3 * 1024 * 1024 + 17}) {
            byte[] data = randomBytes(size, size);
            StoredFile stored = store().store("fs", "big-" + size, null, Map.of(), new ByteArrayInputStream(data));
            assertEquals(size, stored.length());
            assertEquals(sha256(data), stored.sha256());
            try (InputStream in = store().open(stored.id())) {
                assertArrayEquals(data, in.readAllBytes());
            }
        }
    }

    @Test
    void findFiltersByBucketFilenameAndMetadataAndReturnsNewestFirst() throws Exception {
        store().store("find", "v.owl", null, Map.of("projectId", "pf", "n", 1), new ByteArrayInputStream("1".getBytes()));
        Thread.sleep(5);
        StoredFile second = store().store("find", "v.owl", null, Map.of("projectId", "pf", "n", 2), new ByteArrayInputStream("2".getBytes()));
        store().store("find", "other.owl", null, Map.of("projectId", "other"), new ByteArrayInputStream("3".getBytes()));
        store().store("elsewhere", "v.owl", null, Map.of("projectId", "pf"), new ByteArrayInputStream("4".getBytes()));

        List<StoredFile> all = store().find("find", null, Map.of("projectId", "pf"));
        assertEquals(2, all.size());
        assertEquals(second.id(), all.get(0).id());
        assertEquals(second.id(), store().findLatest("find", "v.owl", Map.of("projectId", "pf")).orElseThrow().id());
        assertEquals(Optional.empty(), store().findLatest("find", "v.owl", Map.of("projectId", "nobody")));
        assertEquals(3, store().find("find", null, null).size());
    }

    @Test
    void deleteRemovesTheFileAndDeleteWhereReturnsTheCount() {
        StoredFile one = store().store("del", "x", null, Map.of("projectId", "pd"), new ByteArrayInputStream(randomBytes(2_500_000, 1)));
        store().store("del", "y", null, Map.of("projectId", "pd"), new ByteArrayInputStream("y".getBytes()));
        store().store("del", "z", null, Map.of("projectId", "keep"), new ByteArrayInputStream("z".getBytes()));

        assertTrue(store().delete(one.id()));
        assertFalse(store().delete(one.id()));
        assertThrows(IllegalArgumentException.class, () -> store().open(one.id()));
        assertEquals(1, store().deleteWhere("del", null, Map.of("projectId", "pd")));
        assertEquals(1, store().find("del", null, null).size());
    }

    @Test
    void failureWhileReadingTheSourceLeavesNoRecordAndNoPartialContent() {
        InputStream broken = new InputStream() {
            int calls;

            @Override
            public int read() throws IOException {
                throw new IOException("boom");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (calls++ == 0) {
                    Arrays.fill(b, off, off + len, (byte) 1);
                    return len;
                }
                throw new IOException("boom");
            }
        };

        assertThrows(RuntimeException.class, () -> store().store("fail", "f", null, Map.of(), broken));

        assertEquals(0, store().find("fail", null, null).size());
        assertEquals(0, jdbc().queryForObject(
                "select count(*) from file_chunk fc left join stored_file sf on sf.id = fc.file_id where sf.id is null", Integer.class));
        assertNoPartialContent();
    }

    abstract void assertNoPartialContent();

    @Test
    void manyThreadsStoringAtOnceAllGetTheirOwnIntactFile() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                byte[] data = randomBytes(2_200_000 + n, n);
                StoredFile stored = store().store("conc", "f" + n, null, Map.of("n", n), new ByteArrayInputStream(data));
                try (InputStream in = store().open(stored.id())) {
                    assertArrayEquals(data, in.readAllBytes());
                }
                return stored.id();
            }));
        }
        List<String> ids = new ArrayList<>();
        for (Future<String> f : futures) {
            ids.add(f.get());
        }
        pool.shutdown();

        assertEquals(threads, ids.stream().distinct().count());
        assertEquals(threads, store().find("conc", null, null).size());
    }

    @Test
    void aSixtyFourMegabyteFileStreamsThroughWithTheCorrectChecksum() throws Exception {
        long size = 64L * 1024 * 1024;
        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        InputStream source = new InputStream() {
            long left = size;
            long counter;

            @Override
            public int read() {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (left <= 0) {
                    return -1;
                }
                int n = (int) Math.min(Math.min(len, 64 * 1024), left);
                for (int i = 0; i < n; i++) {
                    b[off + i] = (byte) ((counter + i) * 31);
                }
                counter += n;
                left -= n;
                expected.update(b, off, n);
                return n;
            }
        };

        StoredFile stored = store().store("stream", "big.bin", null, Map.of(), source);

        assertEquals(size, stored.length());
        assertEquals(HexFormat.of().formatHex(expected.digest()), stored.sha256());
        MessageDigest actual = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream in = store().open(stored.id())) {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                actual.update(buffer, 0, read);
                total += read;
            }
        }
        assertEquals(size, total);
        assertEquals(stored.sha256(), HexFormat.of().formatHex(actual.digest()));
    }
}
