package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.model.ProjectStatus;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.StorageManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/ontology/files")
@CrossOrigin
public class ProjectFileController {

    private static final Logger log = LoggerFactory.getLogger(ProjectFileController.class);

    private final StorageManager storageManager;
    private final ProjectMetadataService metadataService;

    public ProjectFileController(StorageManager storageManager,
                                 ProjectMetadataService metadataService) {
        this.storageManager = storageManager;
        this.metadataService = metadataService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listFiles(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "caseSensitive", defaultValue = "false") boolean caseSensitive) {

        List<FileInfo> files = new ArrayList<>();
        for (String projectId : storageManager.listProjectIds()) {
            buildFileInfo(projectId).ifPresent(files::add);
        }

        if (search != null && !search.isBlank()) {
            String needle = caseSensitive ? search : search.toLowerCase(Locale.ROOT);
            files = files.stream()
                    .filter(info -> {
                        String filename = caseSensitive ? info.filename : info.filename.toLowerCase(Locale.ROOT);
                        String projectIdText = caseSensitive ? info.projectId : info.projectId.toLowerCase(Locale.ROOT);
                        return filename.contains(needle) || projectIdText.contains(needle);
                    })
                    .toList();
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "files", files
        ));
    }

    @GetMapping("/{projectId}/download")
    public ResponseEntity<Resource> download(@PathVariable String projectId) {
        try {
            Path ontologyPath = storageManager.findCurrentOntology(projectId)
                    .orElseThrow(() -> new IOException("Ontology file not found for project " + projectId));

            String filename = metadataService.readStatus(projectId)
                    .map(ProjectStatus::filename)
                    .filter(name -> name != null && !name.isBlank())
                    .orElse(ontologyPath.getFileName().toString());

            InputStreamResource resource = new InputStreamResource(Files.newInputStream(ontologyPath));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
            headers.setContentLength(Files.size(ontologyPath));
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            return new ResponseEntity<>(resource, headers, HttpStatus.OK);
        } catch (IOException e) {
            log.error("Failed to download ontology for {}", projectId, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(null);
        }
    }

    private Optional<FileInfo> buildFileInfo(String projectId) {
        try {
            Path ontologyPath = storageManager.findCurrentOntology(projectId).orElse(null);
            if (ontologyPath == null) {
                return Optional.empty();
            }

            ProjectStatus status = metadataService.readStatus(projectId).orElse(null);
            String filename = status != null && status.filename() != null && !status.filename().isBlank()
                    ? status.filename()
                    : ontologyPath.getFileName().toString();

            long length = Files.size(ontologyPath);
            Instant modified = Files.getLastModifiedTime(ontologyPath).toInstant();
            String contentType = Optional.ofNullable(Files.probeContentType(ontologyPath))
                    .orElse("application/octet-stream");

            return Optional.of(new FileInfo(
                    projectId,
                    filename,
                    contentType,
                    length,
                    modified.toString(),
                    projectId
            ));
        } catch (IOException e) {
            log.warn("Failed to build file info for {}", projectId, e);
            return Optional.empty();
        }
    }

    private record FileInfo(
            String id,
            String filename,
            String contentType,
            long length,
            String uploadDate,
            String projectId) {
    }
}

