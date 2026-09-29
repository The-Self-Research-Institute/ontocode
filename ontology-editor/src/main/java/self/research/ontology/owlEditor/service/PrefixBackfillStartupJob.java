package self.research.ontology.owlEditor.service;

import org.bson.Document;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.eclipse.rdf4j.rio.helpers.BasicParserSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.repository.ProjectRepository;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * One-time job, run in the background after startup (web and desktop): projects imported before
 * namespace capture existed have no saved prefixes, so the panel shows none and exports fall back
 * to the shared Fuseki namespace list. For each such project, copy the namespace declarations from
 * its uploaded file into the saved prefixes and clear its Code View cache. A marker document in
 * MongoDB makes it run only once per database (the server's, or each desktop's local one).
 */
@Component
public class PrefixBackfillStartupJob {

    private static final Logger log = LoggerFactory.getLogger(PrefixBackfillStartupJob.class);

    private static final String MIGRATIONS = "app_migrations";
    private static final String MARKER_ID = "prefix-backfill-v1";
    private static final List<String> UPLOADED_FILES = List.of(
            "ontology.current.owl", "ontology.current.ttl", "ontology.current.nt", "ontology.current.jsonld");

    private final ProjectRepository projectRepository;
    private final ProjectMetadataService metadataService;
    private final StorageManager storageManager;
    private final MongoTemplate mongoTemplate;

    public PrefixBackfillStartupJob(ProjectRepository projectRepository, ProjectMetadataService metadataService,
                                    StorageManager storageManager, MongoTemplate mongoTemplate) {
        this.projectRepository = projectRepository;
        this.metadataService = metadataService;
        this.storageManager = storageManager;
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread worker = new Thread(this::runOnce, "prefix-backfill");
        worker.setDaemon(true);
        worker.start();
    }

    void runOnce() {
        try {
            Query marker = Query.query(Criteria.where("_id").is(MARKER_ID));
            if (mongoTemplate.exists(marker, MIGRATIONS)) {
                return;
            }
            int updated = 0, skipped = 0, noFile = 0, failed = 0;
            for (ProjectDocument project : projectRepository.findAll()) {
                switch (backfill(project.getId())) {
                    case "updated" -> updated++;
                    case "no-file" -> noFile++;
                    case "failed" -> failed++;
                    default -> skipped++;
                }
            }
            mongoTemplate.save(new Document("_id", MARKER_ID)
                    .append("completedAt", Instant.now().toString())
                    .append("updated", updated).append("skipped", skipped)
                    .append("noFile", noFile).append("failed", failed), MIGRATIONS);
            log.info("[PREFIX-BACKFILL] Done: updated={} skipped={} noFile={} failed={}",
                    updated, skipped, noFile, failed);
        } catch (Exception e) {
            log.warn("[PREFIX-BACKFILL] Startup backfill did not complete (will retry next start): {}",
                    e.getMessage());
        }
    }

    private String backfill(String projectId) {
        try {
            Map<String, Object> meta = new HashMap<>(metadataService.readMeta(projectId).orElseGet(HashMap::new));
            Map<String, String> existing = savedPrefixes(meta.get("prefixes"));
            if (hasFilePrefixes(existing)) {
                return "skipped";
            }
            Map<String, String> fromFile = prefixesFromUploadedFile(projectId);
            if (fromFile.isEmpty()) {
                return "no-file";
            }
            // Same list a fresh upload ends up with: the file's prefixes, anything already
            // saved, then the standard prefixes getPrefixes() always adds when missing.
            Map<String, String> merged = new LinkedHashMap<>(fromFile);
            merged.putAll(existing);
            OntologyMetadataService.STANDARD_PREFIXES.forEach(merged::putIfAbsent);
            meta.put("prefixes", merged);
            meta.put("prefixCount", merged.size());
            metadataService.writeMeta(projectId, meta);
            storageManager.clearCodeViewCache(projectId);
            log.info("[PREFIX-BACKFILL] Saved {} prefixes for project {}", merged.size(), projectId);
            return "updated";
        } catch (Exception e) {
            log.warn("[PREFIX-BACKFILL] Failed for project {}: {}", projectId, e.getMessage());
            return "failed";
        }
    }

    private static Map<String, String> savedPrefixes(Object raw) {
        Map<String, String> prefixes = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (k != null && v != null) prefixes.put(String.valueOf(k), String.valueOf(v));
            });
        } else if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> e && e.get("prefix") != null && e.get("namespace") != null) {
                    prefixes.put(String.valueOf(e.get("prefix")), String.valueOf(e.get("namespace")));
                }
            }
        }
        return prefixes;
    }

    /** True when at least one saved prefix is more than an app-added default. */
    private static boolean hasFilePrefixes(Map<String, String> prefixes) {
        return prefixes.keySet().stream()
                .map(p -> p.endsWith(":") ? p.substring(0, p.length() - 1) : p)
                .anyMatch(p -> !OntologyMetadataService.STANDARD_PREFIXES.containsKey(p));
    }

    /**
     * Namespace declarations from the uploaded file (ontology.current.*, not ontology.original.*,
     * which is an app export). Stops at the first statement: declarations sit in the header.
     */
    private Map<String, String> prefixesFromUploadedFile(String projectId) throws Exception {
        Map<String, String> found = new LinkedHashMap<>();
        Path dir = storageManager.projectDir(projectId);
        Optional<Path> file = UPLOADED_FILES.stream().map(dir::resolve).filter(Files::exists).findFirst();
        if (file.isEmpty()) {
            return found;
        }
        RDFFormat format = Rio.getParserFormatForFileName(file.get().getFileName().toString())
                .orElse(RDFFormat.RDFXML);
        RDFParser parser = Rio.createParser(format);
        parser.getParserConfig().set(BasicParserSettings.VERIFY_URI_SYNTAX, false);
        RuntimeException stop = new RuntimeException("prefix-scan-done");
        parser.setRDFHandler(new AbstractRDFHandler() {
            @Override
            public void handleNamespace(String prefix, String uri) {
                if (prefix != null && uri != null && !uri.isBlank() && !uri.contains(" ")) {
                    found.putIfAbsent(prefix, uri);
                }
            }

            @Override
            public void handleStatement(Statement st) {
                throw stop;
            }
        });
        try (InputStream in = Files.newInputStream(file.get())) {
            parser.parse(in, "");
        } catch (RuntimeException e) {
            if (e != stop && e.getCause() != stop) {
                throw e;
            }
        }
        return found;
    }
}
