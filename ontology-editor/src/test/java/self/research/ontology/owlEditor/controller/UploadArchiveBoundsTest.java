package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import self.research.ontology.owlEditor.upload.ArchiveLimits;
import self.research.ontology.owlEditor.upload.BoundedGzipInputStream;
import self.research.ontology.owlEditor.upload.UploadLimitExceededException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadArchiveBoundsTest {

    @TempDir
    Path tmp;

    static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    Path zip(String... namesAndSizes) throws IOException {
        Path file = tmp.resolve("pkg-" + System.nanoTime() + ".zip");
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndSizes.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndSizes[i]));
                zip.write(new byte[Integer.parseInt(namesAndSizes[i + 1])]);
                zip.closeEntry();
            }
        }
        return file;
    }

    @Test
    void gzipWithinLimitsDecompressesNormally() throws IOException {
        byte[] original = "<rdf:RDF/>".repeat(100).getBytes(StandardCharsets.UTF_8);
        try (BoundedGzipInputStream in = BoundedGzipInputStream.open(
                new ByteArrayInputStream(gzip(original)), ArchiveLimits.defaults())) {
            assertArrayEquals(original, in.readAllBytes());
        }
    }

    @Test
    void gzipOverTheMaximumUncompressedSizeIsRejected() throws IOException {
        ArchiveLimits limits = new ArchiveLimits(1000, 1_000_000, 10, 1000, 1000);
        BoundedGzipInputStream in = BoundedGzipInputStream.open(new ByteArrayInputStream(gzip(new byte[5000])), limits);
        assertThrows(UploadLimitExceededException.class, in::readAllBytes);
    }

    @Test
    void gzipBombWithAHugeRatioIsRejected() throws IOException {
        byte[] bomb = gzip(new byte[48 * 1024 * 1024]);
        assertTrue(bomb.length < 200_000);
        ArchiveLimits limits = new ArchiveLimits(4L << 30, 200, 10, 1000, 1000);
        BoundedGzipInputStream in = BoundedGzipInputStream.open(new ByteArrayInputStream(bomb), limits);
        UploadLimitExceededException e = assertThrows(UploadLimitExceededException.class, in::readAllBytes);
        assertTrue(e.getMessage().contains("times"));
    }

    @Test
    void zipWithinLimitsExtracts() throws IOException {
        Path target = Files.createDirectory(tmp.resolve("ok"));
        UploadFileSupport.extractOntologyPackage(zip("a.owl", "100", "dir/b.ttl", "50"), target, ArchiveLimits.defaults());
        assertEquals(100, Files.size(target.resolve("a.owl")));
        assertEquals(50, Files.size(target.resolve("dir/b.ttl")));
    }

    @Test
    void zipWithTooManyEntriesIsRejected() throws IOException {
        Path target = Files.createDirectory(tmp.resolve("many"));
        String[] entries = new String[20];
        for (int i = 0; i < 10; i++) {
            entries[i * 2] = "f" + i + ".owl";
            entries[i * 2 + 1] = "1";
        }
        Path pkg = zip(entries);
        ArchiveLimits limits = new ArchiveLimits(1 << 20, 200, 5, 1 << 20, 1 << 20);
        assertThrows(UploadLimitExceededException.class,
                () -> UploadFileSupport.extractOntologyPackage(pkg, target, limits));
    }

    @Test
    void zipWhoseTotalSizeIsTooLargeIsRejected() throws IOException {
        Path target = Files.createDirectory(tmp.resolve("total"));
        Path pkg = zip("a", "100", "b", "100", "c", "100");
        ArchiveLimits limits = new ArchiveLimits(1 << 20, 200, 10, 250, 1000);
        assertThrows(UploadLimitExceededException.class,
                () -> UploadFileSupport.extractOntologyPackage(pkg, target, limits));
    }

    @Test
    void zipEntryLargerThanTheSingleEntryLimitIsRejected() throws IOException {
        Path target = Files.createDirectory(tmp.resolve("single"));
        Path pkg = zip("big.owl", "500");
        ArchiveLimits limits = new ArchiveLimits(1 << 20, 200, 10, 1 << 20, 100);
        assertThrows(UploadLimitExceededException.class,
                () -> UploadFileSupport.extractOntologyPackage(pkg, target, limits));
    }

    @Test
    void zipSlipEntryIsStillRejected() throws IOException {
        Path target = Files.createDirectory(tmp.resolve("slip"));
        Path pkg = zip("../evil.owl", "10");
        IOException e = assertThrows(IOException.class,
                () -> UploadFileSupport.extractOntologyPackage(pkg, target, ArchiveLimits.defaults()));
        assertTrue(e.getMessage().contains("Unsafe"));
        assertTrue(Files.notExists(tmp.resolve("evil.owl")));
    }
}
