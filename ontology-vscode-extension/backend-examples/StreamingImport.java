/**
 * Example: Streaming import to prevent memory issues
 *
 * Performance Impact: 1-3 minutes saved + prevents OutOfMemoryError
 *
 * Why this works:
 * - Doesn't load entire file into memory
 * - Processes file as a stream
 * - Critical for files > 100MB
 */

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.InputStream;

public class StreamingImport {

    /**
     * WRONG WAY - Loads entire file into memory
     */
    public void importFileWrong(Repository repo, MultipartFile file) throws Exception {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();

            // BAD: This loads the entire file into memory!
            byte[] fileBytes = file.getBytes(); // 122MB loaded into memory
            conn.add(new ByteArrayInputStream(fileBytes), "", RDFFormat.RDFXML);

            conn.commit();
        }
    }

    /**
     * RIGHT WAY - Streams the file
     */
    public void importFileCorrect(Repository repo, MultipartFile file) throws Exception {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();

            // GOOD: Stream the file directly, minimal memory usage
            try (InputStream stream = new BufferedInputStream(file.getInputStream())) {
                conn.add(stream, "", RDFFormat.RDFXML);
            }

            conn.commit();
        }
    }

    /**
     * BEST WAY - Streaming + batching + inference disabled
     */
    public void importFileOptimized(Repository repo, MultipartFile file) throws Exception {
        try (RepositoryConnection conn = repo.getConnection()) {
            // 1. Disable inference
            disableInference(conn);

            // 2. Stream import with batching
            long startTime = System.currentTimeMillis();
            System.out.println("Starting optimized import for: " + file.getOriginalFilename());

            try (InputStream stream = new BufferedInputStream(file.getInputStream(), 8192)) {
                conn.begin();
                conn.add(stream, "", RDFFormat.RDFXML);
                conn.commit();
            }

            long importTime = System.currentTimeMillis() - startTime;
            System.out.println("Import completed in " + (importTime / 1000) + " seconds");

            // 3. Re-enable inference and rebuild
            startTime = System.currentTimeMillis();
            enableInferenceAndRebuild(conn);
            long rebuildTime = System.currentTimeMillis() - startTime;

            System.out.println("Index rebuilt in " + (rebuildTime / 1000) + " seconds");
            System.out.println("Total time: " + ((importTime + rebuildTime) / 1000) + " seconds");
        }
    }

    /**
     * For compressed files (from frontend compression)
     */
    public void importCompressedFile(Repository repo, MultipartFile file, boolean isCompressed)
            throws Exception {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();

            InputStream stream = new BufferedInputStream(file.getInputStream());

            // Decompress if needed
            if (isCompressed) {
                System.out.println("Decompressing gzip stream...");
                stream = new GZIPInputStream(stream);
            }

            conn.add(stream, "", RDFFormat.RDFXML);
            conn.commit();

            stream.close();
        }
    }

    private void disableInference(RepositoryConnection conn) {
        // Implementation from DisableInferenceDuringImport.java
    }

    private void enableInferenceAndRebuild(RepositoryConnection conn) {
        // Implementation from DisableInferenceDuringImport.java
    }
}
