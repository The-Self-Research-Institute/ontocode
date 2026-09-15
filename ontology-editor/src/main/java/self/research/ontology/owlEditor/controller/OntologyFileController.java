package self.research.ontology.owlEditor.controller;

import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.model.ProjectStatus;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.StorageManager;
import self.research.ontology.owlEditor.service.owlapi.OwlApiOntologyContext;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/ontology-file")
@CrossOrigin(originPatterns = "*")
public class OntologyFileController {

    private static final Logger log = LoggerFactory.getLogger(OntologyFileController.class);

    @Autowired
    private StorageManager storageManager;

    @Autowired
    private ProjectMetadataService metadataService;

    @Autowired(required = false)
    private OwlApiOntologyContext owlApiContext;

    @GetMapping("/{projectId}")
    public ResponseEntity<?> getOntologyFile(@PathVariable String projectId,
                                             @RequestParam(required = false, defaultValue = "false") boolean forceExport) {
        try {
            log.info("Serving ontology file for project: {} (forceExport={})", projectId, forceExport);

            Optional<ProjectStatus> statusOpt = metadataService.readStatus(projectId);
            if (statusOpt.isPresent()) {
                String state = statusOpt.get().status();
                if ("UPLOADED".equals(state) || "PROCESSING".equals(state) || "INDEXING".equals(state)) {
                    log.info("Ontology for project {} is still {}; deferring file response", projectId, state);
                    return ResponseEntity.status(HttpStatus.ACCEPTED)
                        .header(HttpHeaders.RETRY_AFTER, "2")
                        .body(Map.of(
                            "success", false,
                            "status", state,
                            "projectId", projectId,
                            "message", "Ontology import is still in progress; retry after status is COMPLETED"
                        ));
                }
            }

            if (!forceExport) {

                Path currentFile = storageManager.projectDir(projectId).resolve("ontology.current.owl");
                if (Files.exists(currentFile) && Files.isReadable(currentFile)) {
                    log.info("Found current ontology file: {}", currentFile);
                    Resource resource = new FileSystemResource(currentFile);
                    return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_XML)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + projectId + ".owl\"")
                        .body(resource);
                }

                Path originalFile = storageManager.projectDir(projectId).resolve("ontology.original.owl");
                if (Files.exists(originalFile) && Files.isReadable(originalFile)) {
                    log.info("Found original ontology file: {}", originalFile);
                    Resource resource = new FileSystemResource(originalFile);
                    return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_XML)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + projectId + ".owl\"")
                        .body(resource);
                }
            }

            if (owlApiContext != null && owlApiContext.hasOntology(projectId)) {
                Optional<byte[]> liveExport = exportLiveOwlApiModel(projectId);
                if (liveExport.isPresent()) {
                    log.info("Serving ontology for project {} directly from the live in-memory OWLAPI model", projectId);
                    return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_XML)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + projectId + ".owl\"")
                        .body(liveExport.get());
                }
            }

            log.info("No ontology file on disk, attempting to export from GraphDB for project: {}", projectId);
            try {
                Path exportedFile = storageManager.exportOntology(projectId, "rdfxml");
                if (Files.exists(exportedFile) && Files.isReadable(exportedFile)) {
                    log.info("Successfully exported ontology from GraphDB: {}", exportedFile);
                    Resource resource = new FileSystemResource(exportedFile);
                    return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_XML)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + projectId + ".owl\"")
                        .body(resource);
                }
            } catch (Exception exportException) {
                log.warn("Failed to export ontology from GraphDB for project {}: {}", projectId, exportException.getMessage());
            }

            log.warn("No ontology file found for project: {}", projectId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                    "success", false,
                    "error", "Ontology file not found for project: " + projectId,
                    "projectId", projectId,
                    "message", "Neither ontology.current.owl nor ontology.original.owl exists for this project, and GraphDB export failed"
                ));

        } catch (Exception e) {
            log.error("Error serving ontology file for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "success", false,
                    "error", e.getMessage(),
                    "projectId", projectId
                ));
        }
    }

    private Optional<byte[]> exportLiveOwlApiModel(String projectId) {
        Optional<OWLOntology> ontologyOpt = owlApiContext.ontology(projectId);
        if (ontologyOpt.isEmpty()) {
            return Optional.empty();
        }
        try {
            OWLOntology ontology = ontologyOpt.get();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ontology.getOWLOntologyManager().saveOntology(ontology, new RDFXMLDocumentFormat(), out);
            return Optional.of(out.toByteArray());
        } catch (Exception e) {
            log.warn("Failed to export live OWLAPI model for project {}: {}", projectId, e.getMessage());
            return Optional.empty();
        }
    }

    @RequestMapping(value = "/{projectId}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> checkOntologyFile(@PathVariable String projectId) {
        try {
            Path currentFile = storageManager.projectDir(projectId).resolve("ontology.current.owl");
            Path originalFile = storageManager.projectDir(projectId).resolve("ontology.original.owl");

            if (Files.exists(currentFile) || Files.exists(originalFile)) {
                return ResponseEntity.ok().build();
            }

            try {
                Path exportedFile = storageManager.exportOntology(projectId, "rdfxml");
                if (Files.exists(exportedFile)) {
                    return ResponseEntity.ok().build();
                }
            } catch (Exception e) {
                log.debug("GraphDB export check failed for project {}: {}", projectId, e.getMessage());
            }

            return ResponseEntity.notFound().build();

        } catch (Exception e) {
            log.error("Error checking ontology file for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
