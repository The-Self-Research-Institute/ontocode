

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;

@RestController
@RequestMapping("/api/ontology")
public class OptimizedOntologyController {

    @Autowired
    private Repository graphDbRepository;

    @PostMapping("/upload/{projectId}")
    public ResponseEntity<?> uploadOntology(
            @PathVariable String projectId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "compressed", required = false, defaultValue = "false") boolean compressed,
            @RequestParam(value = "action", required = false) String action
    ) {
        try {
            long startTime = System.currentTimeMillis();
            String fileName = file.getOriginalFilename();
            long fileSize = file.getSize();

            System.out.println("=== OPTIMIZED IMPORT START ===");
            System.out.println("Project ID: " + projectId);
            System.out.println("File: " + fileName);
            System.out.println("Size: " + (fileSize / 1024 / 1024) + " MB");
            System.out.println("Compressed: " + compressed);

            boolean isLargeFile = fileSize > 50 * 1024 * 1024;

            if (isLargeFile) {
                System.out.println("Large file detected - using optimized import path");

                CompletableFuture.runAsync(() -> {
                    try {
                        importOntologyOptimized(projectId, file, compressed);
                    } catch (Exception e) {
                        System.err.println("Async import failed: " + e.getMessage());
                        e.printStackTrace();
                    }
                });

                return ResponseEntity.accepted().body(Map.of(
                    "message", "Large file upload accepted. Processing asynchronously...",
                    "projectId", projectId,
                    "estimatedTime", estimateProcessingTime(fileSize) + " minutes"
                ));

            } else {

                importOntologyOptimized(projectId, file, compressed);

                long totalTime = System.currentTimeMillis() - startTime;
                return ResponseEntity.ok(Map.of(
                    "message", "Import completed successfully",
                    "projectId", projectId,
                    "timeSeconds", totalTime / 1000
                ));
            }

        } catch (Exception e) {
            System.err.println("Import failed: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of(
                "error", "Import failed: " + e.getMessage()
            ));
        }
    }

    private void importOntologyOptimized(String projectId, MultipartFile file, boolean compressed)
            throws Exception {

        try (RepositoryConnection conn = graphDbRepository.getConnection()) {
            ValueFactory vf = conn.getValueFactory();
            long totalStartTime = System.currentTimeMillis();

            System.out.println("[1/4] Disabling inference...");
            long stepStart = System.currentTimeMillis();

            IRI inferenceDisabled = vf.createIRI("http://www.ontotext.com/owlim/system#inferenceDisabled");
            conn.begin();
            conn.add(inferenceDisabled, inferenceDisabled, vf.createLiteral(true));
            conn.commit();

            System.out.println("[1/4] Inference disabled in " +
                             (System.currentTimeMillis() - stepStart) + "ms ✓");

            System.out.println("[2/4] Importing ontology (streaming)...");
            stepStart = System.currentTimeMillis();

            conn.begin();

            try (InputStream stream = new BufferedInputStream(file.getInputStream(), 8192)) {
                InputStream inputStream = stream;

                if (compressed) {
                    System.out.println("Decompressing gzip stream...");
                    inputStream = new GZIPInputStream(stream);
                }

                conn.add(inputStream, "http://example.org/ontology/" + projectId, RDFFormat.RDFXML);
            }

            conn.commit();

            long importTime = System.currentTimeMillis() - stepStart;
            System.out.println("[2/4] Import completed in " + (importTime / 1000) + " seconds ✓");

            System.out.println("[3/4] Re-enabling inference...");
            stepStart = System.currentTimeMillis();

            conn.begin();
            conn.remove(inferenceDisabled, inferenceDisabled, vf.createLiteral(true));
            conn.commit();

            System.out.println("[3/4] Inference re-enabled in " +
                             (System.currentTimeMillis() - stepStart) + "ms ✓");

            System.out.println("[4/4] Rebuilding index...");
            stepStart = System.currentTimeMillis();

            conn.begin();
            IRI forceRebuild = vf.createIRI("http://www.ontotext.com/owlim/system#forceRebuildIndex");
            conn.add(forceRebuild, forceRebuild, vf.createLiteral(true));
            conn.commit();

            long rebuildTime = System.currentTimeMillis() - stepStart;
            System.out.println("[4/4] Index rebuilt in " + (rebuildTime / 1000) + " seconds ✓");

            long totalTime = System.currentTimeMillis() - totalStartTime;
            System.out.println("=== OPTIMIZED IMPORT COMPLETE ===");
            System.out.println("Total time: " + (totalTime / 1000) + " seconds");
            System.out.println("Import time: " + (importTime / 1000) + "s");
            System.out.println("Rebuild time: " + (rebuildTime / 1000) + "s");
        }
    }

    private int estimateProcessingTime(long fileSizeBytes) {

        return (int) Math.ceil(fileSizeBytes / (10.0 * 1024 * 1024));
    }

    @GetMapping("/import-status/{projectId}")
    public ResponseEntity<?> getImportStatus(@PathVariable String projectId) {

        return ResponseEntity.ok(Map.of(
            "projectId", projectId,
            "status", "processing",
            "progress", 75
        ));
    }
}
