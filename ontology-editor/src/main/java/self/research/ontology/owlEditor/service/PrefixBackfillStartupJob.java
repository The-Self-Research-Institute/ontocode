package self.research.ontology.owlEditor.service;

import org.bson.Document;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
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
import org.springframework.data.mongodb.gridfs.GridFsResource;
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
 * to the shared Fuseki namespace list. For each such project, add the namespace declarations from
 * its original upload to the saved prefixes (keeping any the user added by hand) and clear its
 * Code View cache. A marker document in
 * MongoDB makes it run only once per database (the server's, or each desktop's local one).
 */
@Component
public class PrefixBackfillStartupJob {

    private static final Logger log = LoggerFactory.getLogger(PrefixBackfillStartupJob.class);

    private static final String MIGRATIONS = "app_migrations";
    // v2: also reads the original upload from GridFS (v1 only read the on-disk copy, which
    // containers without a data volume lose on every redeploy).
    // v3: also fills old projects that already had prefixes added by hand (v2 skipped them).
    private static final String MARKER_ID = "prefix-backfill-v3";
    private static final List<String> UPLOADED_FILES = List.of(
            "ontology.current.owl", "ontology.current.ttl", "ontology.current.nt", "ontology.current.jsonld");

    private final ProjectRepository projectRepository;
    private final ProjectMetadataService metadataService;
    private final StorageManager storageManager;
    private final MongoTemplate mongoTemplate;
    private final GridFSFileService gridFSFileService;

    public PrefixBackfillStartupJob(ProjectRepository projectRepository, ProjectMetadataService metadataService,
                                    StorageManager storageManager, MongoTemplate mongoTemplate,
                                    GridFSFileService gridFSFileService) {
        this.projectRepository = projectRepository;
        this.metadataService = metadataService;
        this.storageManager = storageManager;
        this.mongoTemplate = mongoTemplate;
        this.gridFSFileService = gridFSFileService;
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
            int updated = 0, skipped = 0, deleted = 0, noFile = 0, failed = 0;
            for (ProjectDocument project : projectRepository.findAll()) {
                switch (backfill(project.getId())) {
                    case "updated" -> updated++;
                    case "deleted" -> deleted++;
                    case "no-file" -> noFile++;
                    case "failed" -> failed++;
                    default -> skipped++;
                }
            }
            mongoTemplate.save(new Document("_id", MARKER_ID)
                    .append("completedAt", Instant.now().toString())
                    .append("updated", updated).append("skipped", skipped).append("deleted", deleted)
                    .append("noFile", noFile).append("failed", failed), MIGRATIONS);
            log.info("[PREFIX-BACKFILL] Done: updated={} skipped={} deleted={} noFile={} failed={}",
                    updated, skipped, deleted, noFile, failed);
        } catch (Exception e) {
            log.warn("[PREFIX-BACKFILL] Startup backfill did not complete (will retry next start): {}",
                    e.getMessage());
        }
    }

    private String backfill(String projectId) {
        try {
            if (isDeletedFile(projectId)) {
                // Its GridFS upload is gone from the library, and the disk copy of a saved project
                // is a re-export that carried the shared Fuseki prefix list, so it is no source.
                return "deleted";
            }
            Map<String, Object> meta = new HashMap<>(metadataService.readMeta(projectId).orElseGet(HashMap::new));
            Map<String, String> existing = savedPrefixes(meta.get("prefixes"));
            HeaderScan scan = scanOriginalUpload(projectId);
            if (scan == null || scan.prefixes.isEmpty()) {
                return "no-file";
            }
            if (wasCapturedAtImport(existing, scan.prefixes)) {
                // Imported with namespace capture: anything missing now was removed by the user.
                return "skipped";
            }
            // Same list a fresh upload ends up with, keeping whatever the user already saved
            // (e.g. prefixes added by hand before this job ran): saved entries win, and the
            // file's prefixes, the default prefix from the ontology IRI (the import's re-save
            // adds it) and the standard prefixes getPrefixes() always adds only fill the gaps.
            Map<String, String> merged = new LinkedHashMap<>(existing);
            Set<String> names = new HashSet<>();
            existing.keySet().forEach(k -> names.add(bareName(k)));
            scan.prefixes.forEach((prefix, ns) -> {
                if (names.add(bareName(prefix))) merged.put(prefix, ns);
            });
            String defaultNs = defaultNamespace(meta.get("ontologyIRI"), scan.ontologyIri);
            if (defaultNs != null && names.add("")) {
                merged.put("", defaultNs);
            }
            OntologyMetadataService.STANDARD_PREFIXES.forEach((prefix, ns) -> {
                if (names.add(prefix)) merged.put(prefix, ns);
            });
            if (merged.size() == existing.size()) {
                return "skipped";
            }
            meta.put("prefixes", merged);
            meta.put("prefixCount", merged.size());
            metadataService.writeMeta(projectId, meta);
            storageManager.clearCodeViewCache(projectId);
            log.info("[PREFIX-BACKFILL] Saved {} prefixes for project {} (from {})",
                    merged.size(), projectId, scan.source);
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

    private static String bareName(String prefix) {
        return prefix.endsWith(":") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    /**
     * True when the saved list already holds the file's own namespaces, i.e. the import captured
     * them. Compared by namespace, so a prefix the user renamed still counts. Projects imported
     * before capture existed have none of them saved: nothing, only standard prefixes, or only
     * prefixes the user added by hand. A file declaring only standard prefixes gives nothing to
     * compare, so any non-standard saved prefix is then taken as the user's complete list.
     */
    private static boolean wasCapturedAtImport(Map<String, String> saved, Map<String, String> fromFile) {
        Set<String> fileNamespaces = new HashSet<>();
        fromFile.forEach((prefix, ns) -> {
            String name = bareName(prefix);
            if (!name.isEmpty() && !"xml".equals(name)
                    && !OntologyMetadataService.STANDARD_PREFIXES.containsKey(name)) {
                fileNamespaces.add(ns);
            }
        });
        if (fileNamespaces.isEmpty()) {
            return saved.keySet().stream().map(PrefixBackfillStartupJob::bareName)
                    .anyMatch(p -> !OntologyMetadataService.STANDARD_PREFIXES.containsKey(p));
        }
        return saved.values().stream().anyMatch(fileNamespaces::contains);
    }

    /** The ontology IRI as a namespace ("…/ontology" → "…/ontology#"), as the import's re-save writes it. */
    private static String defaultNamespace(Object metaOntologyIri, String fileOntologyIri) {
        String iri = metaOntologyIri instanceof String s && !s.isBlank() ? s : fileOntologyIri;
        if (iri == null || iri.isBlank() || iri.contains(" ")) {
            return null;
        }
        return iri.endsWith("#") || iri.endsWith("/") ? iri : iri + "#";
    }

    /**
     * The original upload: GridFS first (the exact file the user uploaded; kept on every platform),
     * then the on-disk copy (ontology.current.*) as a fallback. ontology.original.* is never used:
     * it is an app export.
     */
    private HeaderScan scanOriginalUpload(String projectId) throws Exception {
        HeaderScan fromGridFs = scanGridFs(projectId);
        if (fromGridFs != null && !fromGridFs.prefixes.isEmpty()) {
            return fromGridFs;
        }
        Path dir = storageManager.projectDir(projectId);
        Optional<Path> file = UPLOADED_FILES.stream().map(dir::resolve).filter(Files::exists).findFirst();
        if (file.isEmpty()) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file.get())) {
            return scanHeader(in, file.get().getFileName().toString(), "disk");
        }
    }

    /** True when the project's file was deleted from the library (file_metadata.isDeleted). */
    private boolean isDeletedFile(String projectId) {
        int sep = projectId.indexOf("--");
        if (sep < 0) {
            return false;
        }
        Document fileMeta = mongoTemplate.getDb().getCollection("file_metadata")
                .find(new Document("fileId", projectId.substring(sep + 2))).first();
        return fileMeta != null && Boolean.TRUE.equals(fileMeta.getBoolean("isDeleted"));
    }

    /** Project ids are "<parent>--<fileId>"; file_metadata maps that fileId to its GridFS upload. */
    private HeaderScan scanGridFs(String projectId) throws Exception {
        int sep = projectId.indexOf("--");
        if (sep < 0) {
            return null;
        }
        Document fileMeta = mongoTemplate.getDb().getCollection("file_metadata")
                .find(new Document("fileId", projectId.substring(sep + 2))
                        .append("isDeleted", new Document("$ne", true)))
                .first();
        if (fileMeta == null || fileMeta.getString("gridfsId") == null) {
            return null;
        }
        Optional<GridFsResource> resource = gridFSFileService.getFileById(fileMeta.getString("gridfsId"));
        if (resource.isEmpty()) {
            return null;
        }
        String fileName = fileMeta.getString("fileName") != null ? fileMeta.getString("fileName") : "upload.owl";
        try (InputStream in = resource.get().getInputStream()) {
            return scanHeader(in, fileName, "GridFS");
        }
    }

    /**
     * Namespace declarations from the file header, plus the ontology IRI when the first statement
     * declares it. Stops at the first statement: declarations sit in the header.
     */
    private static HeaderScan scanHeader(InputStream in, String fileName, String source) throws Exception {
        HeaderScan scan = new HeaderScan(source);
        RDFFormat format = Rio.getParserFormatForFileName(fileName).orElse(RDFFormat.RDFXML);
        RDFParser parser = Rio.createParser(format);
        parser.getParserConfig().set(BasicParserSettings.VERIFY_URI_SYNTAX, false);
        RuntimeException stop = new RuntimeException("prefix-scan-done");
        parser.setRDFHandler(new AbstractRDFHandler() {
            @Override
            public void handleNamespace(String prefix, String uri) {
                if (prefix != null && uri != null && !uri.isBlank() && !uri.contains(" ")) {
                    scan.prefixes.putIfAbsent(prefix, uri);
                }
            }

            @Override
            public void handleStatement(Statement st) {
                if (RDF.TYPE.equals(st.getPredicate()) && OWL.ONTOLOGY.equals(st.getObject())
                        && st.getSubject().isIRI()) {
                    scan.ontologyIri = st.getSubject().stringValue();
                }
                throw stop;
            }
        });
        try {
            parser.parse(in, "");
        } catch (RuntimeException e) {
            if (e != stop && e.getCause() != stop) {
                throw e;
            }
        }
        return scan;
    }

    private static final class HeaderScan {
        final Map<String, String> prefixes = new LinkedHashMap<>();
        final String source;
        String ontologyIri;

        HeaderScan(String source) {
            this.source = source;
        }
    }
}
