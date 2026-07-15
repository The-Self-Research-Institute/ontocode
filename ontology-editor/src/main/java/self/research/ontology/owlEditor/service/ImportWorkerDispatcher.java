package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import self.research.ontology.owlEditor.dto.ImportWorkerRequest;
import self.research.ontology.owlEditor.model.ImportOptions;

import java.nio.file.Path;

@Service
public class ImportWorkerDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ImportWorkerDispatcher.class);

    private final WebClient.Builder webClientBuilder;
    private final ProjectImportService importService;

    @Value("${import.worker.url:}")
    private String workerUrl;

    public ImportWorkerDispatcher(WebClient.Builder webClientBuilder,
                                  ProjectImportService importService) {
        this.webClientBuilder = webClientBuilder;
        this.importService = importService;
    }

    public void dispatch(String projectId,
                         Path owlFile,
                         String ownerEmail,
                         String filename,
                         String gridfsFileId,
                         ImportOptions options) {
        if (workerUrl == null || workerUrl.isBlank()) {
            importService.submitImport(projectId, owlFile, ownerEmail, options);
            return;
        }

        ImportWorkerRequest request = new ImportWorkerRequest();
        request.setProjectId(projectId);
        request.setFilename(filename);
        request.setOwnerEmail(ownerEmail);
        request.setGridfsFileId(gridfsFileId);
        request.setImportMode(options.getMode().name().toLowerCase());
        request.setPartition(options.getPartitionStrategy().name().toLowerCase());
        request.setFileSizeBytes(owlFile.toFile().length());

        log.info("[ImportWorker] Dispatching import for {} to {}", projectId, workerUrl);
        webClientBuilder.build()
                .post()
                .uri(workerUrl + "/api/import-worker/submit")
                .bodyValue(request)
                .retrieve()
                .toBodilessEntity()
                .doOnError(err -> log.error("[ImportWorker] Dispatch failed: {}", err.getMessage()))
                .subscribe();
    }
}
