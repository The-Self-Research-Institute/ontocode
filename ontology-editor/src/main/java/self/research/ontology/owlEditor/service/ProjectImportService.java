package self.research.ontology.owlEditor.service;

import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.ProjectStatus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Background job runner that streams uploaded OWL files into TDB2 and refreshes metadata.
 */
@Service
public class ProjectImportService {

    private static final Logger log = LoggerFactory.getLogger(ProjectImportService.class);

    private final Executor owlParsingExecutor;
    private final Tdb2DatasetService datasetService;
    private final OntologyIndexService indexService;
    private final ProjectMetadataService metadataService;
    private final StorageManager storageManager;

    public ProjectImportService(@Qualifier("owlParsingExecutor") Executor owlParsingExecutor,
                                Tdb2DatasetService datasetService,
                                OntologyIndexService indexService,
                                ProjectMetadataService metadataService,
                                StorageManager storageManager) {
        this.owlParsingExecutor = owlParsingExecutor;
        this.datasetService = datasetService;
        this.indexService = indexService;
        this.metadataService = metadataService;
        this.storageManager = storageManager;
    }

    public void submitImport(String projectId, Path owlFile) {
        owlParsingExecutor.execute(() -> runImport(projectId, owlFile));
    }

    private void runImport(String projectId, Path owlFile) {
        String filename = metadataService.readStatus(projectId)
                .map(ProjectStatus::filename)
                .orElse(owlFile.getFileName().toString());

        metadataService.writeStatus(projectId, ProjectStatus.processing(filename));
        try {
            Lang lang = detectLang(owlFile);
            datasetService.clearDataset(projectId);
            try (InputStream in = Files.newInputStream(owlFile)) {
                datasetService.bulkLoad(projectId, in, lang);
            }

            Path current = storageManager.resolveProjectFile(projectId, "ontology.current." + extensionFor(lang));
            Files.createDirectories(current.getParent());
            Files.copy(owlFile, current, StandardCopyOption.REPLACE_EXISTING);

            Map<String, Object> meta = indexService.computeMetadata(projectId);
            metadataService.writeMeta(projectId, meta);
            metadataService.writeStatus(projectId, ProjectStatus.completed(filename));
            log.info("Completed import for project {}", projectId);
        } catch (Exception e) {
            log.error("Import failed for {}", projectId, e);
            metadataService.writeStatus(projectId, ProjectStatus.error(filename, e.getMessage()));
        }
    }

    private Lang detectLang(Path file) {
        Lang lang = RDFDataMgr.determineLang(file.getFileName().toString(), null, null);
        return lang != null ? lang : Lang.RDFXML;
    }

    private String extensionFor(Lang lang) {
        if (lang == null) {
            return "owl";
        }
        if (Lang.TURTLE.equals(lang)) {
            return "ttl";
        }
        if (Lang.NTRIPLES.equals(lang)) {
            return "nt";
        }
        if (Lang.JSONLD.equals(lang)) {
            return "jsonld";
        }
        return "owl";
    }
}

