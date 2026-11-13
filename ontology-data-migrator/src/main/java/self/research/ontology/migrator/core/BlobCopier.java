package self.research.ontology.migrator.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

public final class BlobCopier {

    private static final int BUFFER = 1024 * 1024;

    private final BlobSource source;
    private final BlobSink sink;
    private final boolean dryRun;

    public BlobCopier(BlobSource source, BlobSink sink, boolean dryRun) {
        this.source = source;
        this.sink = sink;
        this.dryRun = dryRun;
    }

    public MigrationReport.BlobStats copy(String bucket) {
        MigrationReport.BlobStats stats = new MigrationReport.BlobStats(bucket);
        if (!source.exists(bucket)) {
            return stats;
        }
        try (CloseableIterator<BlobFile> files = source.list(bucket)) {
            while (files.hasNext()) {
                copyOne(files.next(), stats);
            }
        }
        return stats;
    }

    private void copyOne(BlobFile file, MigrationReport.BlobStats stats) {
        stats.files++;
        stats.bytes += file.length();
        if (dryRun) {
            stats.copied++;
            return;
        }
        try {
            Optional<BlobSink.Stored> existing = sink.lookup(file.id());
            if (existing.isPresent() && existing.get().length() == file.length()) {
                stats.alreadyPresent++;
                return;
            }
            BlobSink.Stored stored;
            try (InputStream in = source.open(file)) {
                stored = sink.store(file, in);
            }
            if (stored.length() != file.length()) {
                sink.remove(file.id());
                stats.sha256Mismatches++;
                stats.failed++;
                stats.problem(file.id() + ": copied " + stored.length() + " bytes, source says " + file.length());
                return;
            }
            stats.copied++;
        } catch (IOException | RuntimeException e) {
            stats.failed++;
            stats.problem(file.id() + ": " + e.getMessage());
        }
    }

    public MigrationReport.VerifyStats verify(String bucket, int sampleSize) {
        MigrationReport.VerifyStats stats = new MigrationReport.VerifyStats(bucket);
        if (!source.exists(bucket)) {
            return stats;
        }
        for (BlobFile file : source.sample(bucket, sampleSize)) {
            stats.sampled++;
            Optional<BlobSink.Stored> stored = sink.lookup(file.id());
            if (stored.isEmpty()) {
                stats.keysMissing++;
                stats.example(file.id() + ": not copied");
                continue;
            }
            try (InputStream in = source.open(file)) {
                String hash = sha256(in);
                if (!hash.equals(stored.get().sha256()) || stored.get().length() != file.length()) {
                    stats.fieldMismatches++;
                    stats.example(file.id() + ": content differs from source");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return stats;
    }

    public static String sha256(InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream din = new DigestInputStream(in, digest)) {
                byte[] buffer = new byte[BUFFER];
                while (din.read(buffer) >= 0) {
                    continue;
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
