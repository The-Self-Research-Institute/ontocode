

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.InputStream;

public class StreamingImport {

    public void importFileWrong(Repository repo, MultipartFile file) throws Exception {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();

            byte[] fileBytes = file.getBytes();
            conn.add(new ByteArrayInputStream(fileBytes), "", RDFFormat.RDFXML);

            conn.commit();
        }
    }

    public void importFileCorrect(Repository repo, MultipartFile file) throws Exception {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();

            try (InputStream stream = new BufferedInputStream(file.getInputStream())) {
                conn.add(stream, "", RDFFormat.RDFXML);
            }

            conn.commit();
        }
    }

    public void importFileOptimized(Repository repo, MultipartFile file) throws Exception {
        try (RepositoryConnection conn = repo.getConnection()) {

            disableInference(conn);

            long startTime = System.currentTimeMillis();
            System.out.println("Starting optimized import for: " + file.getOriginalFilename());

            try (InputStream stream = new BufferedInputStream(file.getInputStream(), 8192)) {
                conn.begin();
                conn.add(stream, "", RDFFormat.RDFXML);
                conn.commit();
            }

            long importTime = System.currentTimeMillis() - startTime;
            System.out.println("Import completed in " + (importTime / 1000) + " seconds");

            startTime = System.currentTimeMillis();
            enableInferenceAndRebuild(conn);
            long rebuildTime = System.currentTimeMillis() - startTime;

            System.out.println("Index rebuilt in " + (rebuildTime / 1000) + " seconds");
            System.out.println("Total time: " + ((importTime + rebuildTime) / 1000) + " seconds");
        }
    }

    public void importCompressedFile(Repository repo, MultipartFile file, boolean isCompressed)
            throws Exception {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();

            InputStream stream = new BufferedInputStream(file.getInputStream());

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

    }

    private void enableInferenceAndRebuild(RepositoryConnection conn) {

    }
}
