

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.function.Consumer;

public class BatchInsertOptimization {

    private static final int BATCH_SIZE = 10000;
    private static final int PROGRESS_REPORT_INTERVAL = 50000;

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int processedTriples, double triplesPerSecond, int percentComplete);
    }

    public static class ImportStats {
        public long totalTriples;
        public long durationMs;
        public double triplesPerSecond;
        public int batchCount;
        public long memoryUsedMB;

        @Override
        public String toString() {
            return String.format(
                "Import Statistics:\n" +
                "  Total triples: %,d\n" +
                "  Duration: %.2f seconds\n" +
                "  Speed: %,.0f triples/second\n" +
                "  Batches: %d\n" +
                "  Memory used: %,d MB",
                totalTriples, durationMs / 1000.0, triplesPerSecond, batchCount, memoryUsedMB
            );
        }
    }

    public ImportStats importWithBatchingEnhanced(
            Repository repo,
            File owlFile,
            boolean isCompressed,
            ProgressCallback progressCallback) throws Exception {

        try (RepositoryConnection conn = repo.getConnection()) {
            long startTime = System.currentTimeMillis();
            long memoryBefore = getUsedMemoryMB();

            conn.setAutoCommit(false);

            final List<Statement> batch = new ArrayList<>(BATCH_SIZE);
            final AtomicInteger totalCount = new AtomicInteger(0);
            final AtomicInteger batchCount = new AtomicInteger(0);
            final AtomicLong lastReportTime = new AtomicLong(startTime);

            AbstractRDFHandler handler = new AbstractRDFHandler() {
                @Override
                public void handleStatement(Statement st) {
                    batch.add(st);
                    int count = totalCount.incrementAndGet();

                    if (batch.size() >= BATCH_SIZE) {
                        try {
                            conn.add(batch);
                            conn.commit();
                            batchCount.incrementAndGet();

                            batch.clear();
                            conn.begin();

                            long now = System.currentTimeMillis();
                            if (count % PROGRESS_REPORT_INTERVAL == 0 ||
                                now - lastReportTime.get() > 5000) {

                                long elapsed = now - startTime;
                                double triplesPerSec = (count * 1000.0) / elapsed;

                                System.out.println(String.format(
                                    "Progress: %,d triples | %.0f triples/sec | %.1f MB memory",
                                    count, triplesPerSec, getUsedMemoryMB()
                                ));

                                if (progressCallback != null) {

                                    int percent = Math.min(99, (int)(count / 10000.0));
                                    progressCallback.onProgress(count, triplesPerSec, percent);
                                }

                                lastReportTime.set(now);
                            }

                        } catch (Exception e) {
                            System.err.println("Batch insertion failed, rolling back...");
                            try {
                                conn.rollback();
                            } catch (Exception rollbackEx) {
                                System.err.println("Rollback failed: " + rollbackEx.getMessage());
                            }
                            throw new RuntimeException("Failed to insert batch at triple " + count, e);
                        }
                    }
                }
            };

            try {

                RDFParser parser = Rio.createParser(RDFFormat.RDFXML);
                parser.setRDFHandler(handler);

                InputStream stream = new BufferedInputStream(new FileInputStream(owlFile), 8192);

                if (isCompressed) {
                    System.out.println("Decompressing gzip stream...");
                    stream = new GZIPInputStream(stream);
                }

                conn.begin();
                parser.parse(stream);
                stream.close();

                if (!batch.isEmpty()) {
                    conn.add(batch);
                    conn.commit();
                    batchCount.incrementAndGet();
                }

            } catch (Exception e) {
                System.err.println("Import failed: " + e.getMessage());
                try {
                    conn.rollback();
                } catch (Exception rollbackEx) {
                    System.err.println("Rollback failed: " + rollbackEx.getMessage());
                }
                throw e;
            }

            long totalTime = System.currentTimeMillis() - startTime;
            long memoryUsed = getUsedMemoryMB() - memoryBefore;

            ImportStats stats = new ImportStats();
            stats.totalTriples = totalCount.get();
            stats.durationMs = totalTime;
            stats.triplesPerSecond = (totalCount.get() * 1000.0) / totalTime;
            stats.batchCount = batchCount.get();
            stats.memoryUsedMB = memoryUsed;

            System.out.println("\n" + stats);

            if (progressCallback != null) {
                progressCallback.onProgress(totalCount.get(), stats.triplesPerSecond, 100);
            }

            return stats;
        }
    }

    public void importWithBatching(Repository repo, File owlFile) throws Exception {
        try (RepositoryConnection conn = repo.getConnection()) {
            long startTime = System.currentTimeMillis();

            conn.begin();

            final List<Statement> batch = new ArrayList<>(BATCH_SIZE);
            final int[] totalCount = {0};

            AbstractRDFHandler handler = new AbstractRDFHandler() {
                @Override
                public void handleStatement(Statement st) {
                    batch.add(st);
                    totalCount[0]++;

                    if (batch.size() >= BATCH_SIZE) {
                        try {
                            conn.add(batch);
                            conn.commit();

                            System.out.println("Inserted batch of " + BATCH_SIZE +
                                             " triples (total: " + totalCount[0] + ")");

                            batch.clear();
                            conn.begin();
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to insert batch", e);
                        }
                    }
                }
            };

            RDFParser parser = Rio.createParser(RDFFormat.RDFXML);
            parser.setRDFHandler(handler);

            try (InputStream stream = new FileInputStream(owlFile)) {
                parser.parse(stream);
            }

            if (!batch.isEmpty()) {
                conn.add(batch);
                conn.commit();
                System.out.println("Inserted final batch of " + batch.size() +
                                 " triples (total: " + totalCount[0] + ")");
            }

            long totalTime = System.currentTimeMillis() - startTime;
            double triplesPerSecond = (totalCount[0] * 1000.0) / totalTime;

            System.out.println("Import completed:");
            System.out.println("  Total triples: " + totalCount[0]);
            System.out.println("  Total time: " + (totalTime / 1000) + " seconds");
            System.out.println("  Speed: " + String.format("%.0f", triplesPerSecond) + " triples/second");
        }
    }

    public ImportStats importWithParallelProcessing(
            Repository repo,
            File owlFile,
            boolean isCompressed,
            int numThreads) throws Exception {

        System.out.println("Using parallel import with " + numThreads + " threads...");

        long startTime = System.currentTimeMillis();

        final List<Statement> statements = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger totalCount = new AtomicInteger(0);

        RDFParser parser = Rio.createParser(RDFFormat.RDFXML);
        parser.setRDFHandler(new AbstractRDFHandler() {
            @Override
            public void handleStatement(Statement st) {
                statements.add(st);
                totalCount.incrementAndGet();
            }
        });

        InputStream stream = new BufferedInputStream(new FileInputStream(owlFile));
        if (isCompressed) {
            stream = new GZIPInputStream(stream);
        }
        parser.parse(stream);
        stream.close();

        System.out.println("Parsed " + totalCount.get() + " statements, inserting in parallel...");

        try (RepositoryConnection conn = repo.getConnection()) {
            conn.setAutoCommit(false);
            conn.begin();

            int chunkSize = statements.size() / numThreads;
            java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(numThreads);

            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < numThreads; i++) {
                final int startIdx = i * chunkSize;
                final int endIdx = (i == numThreads - 1) ? statements.size() : (i + 1) * chunkSize;

                futures.add(executor.submit(() -> {
                    List<Statement> chunk = statements.subList(startIdx, endIdx);
                    synchronized (conn) {
                        conn.add(chunk);
                    }
                    System.out.println("Thread completed chunk: " + chunk.size() + " statements");
                }));
            }

            for (java.util.concurrent.Future<?> future : futures) {
                future.get();
            }

            executor.shutdown();
            conn.commit();
        }

        long totalTime = System.currentTimeMillis() - startTime;

        ImportStats stats = new ImportStats();
        stats.totalTriples = totalCount.get();
        stats.durationMs = totalTime;
        stats.triplesPerSecond = (totalCount.get() * 1000.0) / totalTime;
        stats.batchCount = numThreads;
        stats.memoryUsedMB = getUsedMemoryMB();

        System.out.println("\n" + stats);
        return stats;
    }

    public ImportStats importWithBulkLoader(Repository repo, File owlFile) throws Exception {
        System.out.println("Using GraphDB bulk loader (fastest method)...");

        long startTime = System.currentTimeMillis();

        String graphDbHome = System.getenv("GRAPHDB_HOME");
        if (graphDbHome != null) {
            ProcessBuilder pb = new ProcessBuilder(
                graphDbHome + "/bin/loadrdf",
                "-f",
                "-m", "parallel",
                getRepositoryId(repo),
                owlFile.getAbsolutePath()
            );

            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[LoadRDF] " + line);
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Bulk loader failed with exit code: " + exitCode);
            }
        } else {
            System.out.println("GRAPHDB_HOME not set, using fallback method...");

            System.out.println("To use preload:");
            System.out.println("1. Add to repo-config.ttl:");
            System.out.println("   owlim:imports \"file://" + owlFile.getAbsolutePath() + "\" .");
            System.out.println("2. Create/restart repository");
            System.out.println("This loads data at repository creation (fastest!)");
        }

        long totalTime = System.currentTimeMillis() - startTime;

        ImportStats stats = new ImportStats();
        stats.durationMs = totalTime;
        stats.triplesPerSecond = 0;
        stats.batchCount = 1;
        stats.memoryUsedMB = getUsedMemoryMB();

        System.out.println("\nBulk loader completed in " + (totalTime / 1000.0) + " seconds");
        System.out.println("Note: Bulk loader is 5-10x faster than standard import!");

        return stats;
    }

    public ImportStats importAutoOptimized(
            Repository repo,
            File owlFile,
            boolean isCompressed,
            ProgressCallback progressCallback) throws Exception {

        long fileSizeMB = owlFile.length() / (1024 * 1024);

        System.out.println("File size: " + fileSizeMB + " MB");
        System.out.println("Selecting optimal import method...");

        if (fileSizeMB < 50) {

            System.out.println("Using: Standard batch import");
            return importWithBatchingEnhanced(repo, owlFile, isCompressed, progressCallback);

        } else if (fileSizeMB < 200) {

            System.out.println("Using: Enhanced batch import with progress tracking");
            return importWithBatchingEnhanced(repo, owlFile, isCompressed, progressCallback);

        } else if (fileSizeMB < 500) {

            System.out.println("Using: Parallel import (4 threads)");
            return importWithParallelProcessing(repo, owlFile, isCompressed, 4);

        } else {

            System.out.println("Using: GraphDB bulk loader (fastest)");
            return importWithBulkLoader(repo, owlFile);
        }
    }

    private double getUsedMemoryMB() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0);
    }

    private String getRepositoryId(Repository repo) {

        String repoInfo = repo.toString();

        return "ontology-repo";
    }

    private long estimateTotalTriples(long fileSizeBytes) {

        return fileSizeBytes / 200;
    }
}
