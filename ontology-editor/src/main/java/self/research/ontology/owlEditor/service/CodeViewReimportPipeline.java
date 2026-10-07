package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.util.PerfPhases;
import self.research.ontology.owlEditor.util.RdfFiles;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.model.ImportOptions;
import self.research.ontology.owlEditor.util.OWLFormatConverter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class CodeViewReimportPipeline {

    private final StorageManager storageManager;
    private final SparqlDatasetService datasetService;
    private final ProjectMetadataService metadataService;
    private final CodeViewHistoryRecorder historyRecorder;

    @Autowired(required = false)
    @Nullable
    private OntologyMutationService ontologyMutationService;

    @Autowired(required = false)
    @Nullable
    private ProjectOntologyCache ontologyCache;

    @Autowired(required = false)
    @Nullable
    private HierarchyIndexService hierarchyIndexService;

    @Autowired(required = false)
    @Nullable
    private OntologyQueryService ontologyQueryService;

    @Autowired(required = false)
    @Nullable
    private EditorReasonerCacheService editorReasonerCache;

    @Autowired(required = false)
    @Nullable
    private ChangeSetBroadcaster changeSetBroadcaster;

    @Autowired(required = false)
    @Nullable
    private OntologyIndexService indexService;

    @Autowired(required = false)
    @Qualifier("metadataExecutor")
    @Nullable
    private Executor metadataExecutor;

    private final Map<String, AtomicLong> metadataRefreshSeq = new ConcurrentHashMap<>();

    private enum MetadataRefresh { NOW, BACKGROUND }

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    public CodeViewReimportPipeline(StorageManager storageManager,
                                     SparqlDatasetService datasetService,
                                     ProjectMetadataService metadataService,
                                     OntologyHistoryService historyService,
                                     DraftTrackingService draftTrackingService) {
        this.storageManager = storageManager;
        this.datasetService = datasetService;
        this.metadataService = metadataService;
        this.historyRecorder = new CodeViewHistoryRecorder(historyService, draftTrackingService);
    }

    public record ReimportRequest(String projectId, String format, Path contentFile, boolean draft,
                                   String userId, String username, String targetGraphOverride,
                                   Path oldContentFileForDiff, boolean skipSanitization, ChangeOrigin origin) {
        public ReimportRequest(String projectId, String format, Path contentFile, boolean draft,
                               String userId, String username, String targetGraphOverride,
                               Path oldContentFileForDiff, boolean skipSanitization) {
            this(projectId, format, contentFile, draft, userId, username, targetGraphOverride,
                    oldContentFileForDiff, skipSanitization, null);
        }
    }

    public record ReimportResult(String format, RDFFormat rdfFormat, long sourceVersion,
                                 boolean cacheMatchesSubmittedContent) {
        public ReimportResult(String format, RDFFormat rdfFormat, long sourceVersion) {
            this(format, rdfFormat, sourceVersion, false);
        }
    }

    public ReimportResult reimport(ReimportRequest req) throws IOException {
        String format = req.format();
        boolean isOwlApiFormat = isOwlApiFormat(format);
        ReimportFiles files = new ReimportFiles();
        PerfPhases perf = new PerfPhases();
        boolean completed = false;

        try {
            prepareSource(req, isOwlApiFormat, files);

            perf.mark("prepareSource");
            log.info("[CODE-VIEW-SAVE] Reimporting {} bytes into GraphDB as {} (draft={}, targetGraph={})",
                    Files.size(files.importSourceFile), files.rdfFormat, req.draft(), req.targetGraphOverride());
            importWithRetry(req, files);
            log.info("[CODE-VIEW-SAVE] GraphDB reimport complete");

            invalidateReasonerCaches(req.projectId());

            perf.mark("graphImport");
            ChangeOrigin origin = recordHistoryDiff(req, files);

            perf.mark("historyDiff");
            if (req.draft()) {
                invalidateAfterDraftReplaced(req.projectId(), req.userId());
                perf.mark("invalidate");
                completed = true;
                return new ReimportResult(format, files.rdfFormat,
                        storageManager.getDraftGraphVersion(req.projectId(), req.userId()), false);
            }
            invalidateAfterGraphReplaced(req.projectId(), MetadataRefresh.NOW);
            log.info("[CODE-VIEW-SAVE] All format caches cleared");
            perf.mark("invalidate");

            String cachedContent = isOwlApiFormat
                    ? Files.readString(req.contentFile(), StandardCharsets.UTF_8)
                    : Files.readString(files.importSourceFile, StandardCharsets.UTF_8);
            storageManager.storeCodeViewCache(req.projectId(), cachedContent, format);
            log.info("[CODE-VIEW-SAVE] Current format cache restored");
            perf.mark("cacheWrite");

            completed = true;
            announce(req.projectId(), req.userId(), req.username(), origin);
            boolean cacheMatches = !files.reserializedOnRetry && (isOwlApiFormat || req.skipSanitization());
            return new ReimportResult(format, files.rdfFormat, storageManager.getPublicGraphVersion(req.projectId()),
                    cacheMatches);
        } finally {
            log.info("[CODE-VIEW-SAVE] [PERF] reimport project={} format={} bytes={} outcome={} {}", req.projectId(),
                    format, RdfFiles.sizeOrUnknown(req.contentFile()), completed ? "ok" : "failed", perf.summary());
            if (files.generatedFile != null) {
                Files.deleteIfExists(files.generatedFile);
            }
            if (files.pristineCopy != null) {
                Files.deleteIfExists(files.pristineCopy);
            }
        }
    }

    private static final class ReimportFiles {
        RDFFormat rdfFormat;
        Path importSourceFile;
        Path retrySourceFile;
        Path pristineCopy;
        Path generatedFile;
        boolean reserializedOnRetry;
    }

    private static boolean isOwlApiFormat(String format) {
        return format.equalsIgnoreCase("owlxml")
                || format.equalsIgnoreCase("manchester")
                || format.equalsIgnoreCase("manchestersyntax")
                || format.equalsIgnoreCase("functional")
                || format.equalsIgnoreCase("functionalsyntax");
    }

    private void prepareSource(ReimportRequest req, boolean isOwlApiFormat, ReimportFiles files) throws IOException {
        String format = req.format();
        if (isOwlApiFormat) {
            files.importSourceFile = convertToRdfXml(req.contentFile());
            files.generatedFile = files.importSourceFile;
            files.retrySourceFile = req.contentFile();
            files.rdfFormat = RDFFormat.RDFXML;
            log.info("[CODE-VIEW-SAVE] Converted {} to RDF/XML ({} bytes)", format, Files.size(files.importSourceFile));
            return;
        }
        files.pristineCopy = Files.createTempFile("codeview-pristine-", "." + storageManager.extensionFor(format));
        Files.copy(req.contentFile(), files.pristineCopy, StandardCopyOption.REPLACE_EXISTING);
        if (req.skipSanitization()) {
            log.info("[CODE-VIEW-SAVE] Skipping sanitization/OWL-API reserialization for format {} "
                    + "(caller already validated the content and needs line-position stability)", format);
        } else {
            try {
                OWLFormatConverter.sanitizeFileOnDisk(req.contentFile());
                log.info("[CODE-VIEW-SAVE] Sanitization completed for format: {}", format);
            } catch (Exception sanitizeEx) {
                log.warn("[CODE-VIEW-SAVE] Sanitization failed (continuing with original): {}", sanitizeEx.getMessage());
            }
        }
        files.importSourceFile = req.contentFile();
        files.retrySourceFile = files.pristineCopy;
        files.rdfFormat = switch (format.toLowerCase(Locale.ROOT)) {
            case "turtle", "ttl" -> RDFFormat.TURTLE;
            case "ntriples", "nt" -> RDFFormat.NTRIPLES;
            default -> RDFFormat.RDFXML;
        };
    }

    private void importWithRetry(ReimportRequest req, ReimportFiles files) throws IOException {
        try {
            streamIntoGraphDb(req.projectId(), files.importSourceFile, files.rdfFormat, req.targetGraphOverride());
        } catch (RuntimeException bulkEx) {
            if (files.rdfFormat == RDFFormat.RDFXML && isXmlStructuralError(bulkEx)) {
                log.warn("[CODE-VIEW-SAVE] RDF/XML reimport failed with structural XML error; retrying after OWL API re-serialization for project: {}. Error: {}",
                        req.projectId(), bulkEx.getMessage());
                Path retryConverted = convertToRdfXml(files.retrySourceFile);
                if (files.generatedFile != null) {
                    Files.deleteIfExists(files.generatedFile);
                }
                files.importSourceFile = retryConverted;
                files.generatedFile = retryConverted;
                files.reserializedOnRetry = true;
                streamIntoGraphDb(req.projectId(), files.importSourceFile, RDFFormat.RDFXML, req.targetGraphOverride());
                log.info("[CODE-VIEW-SAVE] OWL API re-serialization retry succeeded ({} bytes)", Files.size(files.importSourceFile));
            } else {
                throw bulkEx;
            }
        }
    }

    private ChangeOrigin recordHistoryDiff(ReimportRequest req, ReimportFiles files) {
        if (req.oldContentFileForDiff() == null) {
            return null;
        }
        ChangeOrigin origin = req.origin() != null ? req.origin() : ChangeOrigin.manual();
        try {
            Model oldModel = parseToModel(req.oldContentFileForDiff(), RdfFiles.snapshotFormat(req.oldContentFileForDiff()));
            Model newModel = parseToModel(files.importSourceFile, files.rdfFormat);
            historyRecorder.record(req.projectId(), CodeViewHistoryRecorder.effectiveUserId(req.userId(), desktopMode),
                    CodeViewHistoryRecorder.effectiveUsername(req.username()), oldModel, newModel, req.draft(), origin);
            return origin;
        } catch (Exception diffEx) {
            log.warn("[CODE-VIEW-SAVE] Failed to record change history diff (save itself succeeded): {}", diffEx.getMessage());
            return null;
        }
    }

    private void announce(String projectId, String userId, String username, ChangeOrigin origin) {
        if (changeSetBroadcaster != null && origin != null) {
            changeSetBroadcaster.changeSetApplied(projectId, CodeViewHistoryRecorder.effectiveUserId(userId, desktopMode),
                    CodeViewHistoryRecorder.effectiveUsername(username), origin);
        }
    }

    public long restoreSnapshot(String projectId, Path snapshot) throws IOException {
        return restoreSnapshot(projectId, snapshot, false, null);
    }

    public long restoreSnapshot(String projectId, Path snapshot, boolean draft, String userId) throws IOException {
        if (snapshot == null || !Files.isRegularFile(snapshot)) {
            throw new IOException("The pre-apply snapshot is missing, so the project can't be restored from it");
        }
        log.warn("[CODE-VIEW-SAVE] Restoring project {} from pre-apply snapshot {} ({} bytes, draft={})",
                projectId, snapshot.getFileName(), Files.size(snapshot), draft);
        String targetGraphOverride = draft ? datasetService.getDraftGraphUri(projectId, userId) : null;
        streamIntoGraphDb(projectId, snapshot, RdfFiles.snapshotFormat(snapshot), targetGraphOverride);
        invalidateReasonerCaches(projectId);
        if (draft) {
            invalidateAfterDraftReplaced(projectId, userId);
            log.info("[CODE-VIEW-SAVE] Project {} draft (user {}) restored from its pre-apply snapshot", projectId, userId);
            return storageManager.getDraftGraphVersion(projectId, userId);
        }
        invalidateAfterGraphReplaced(projectId, MetadataRefresh.BACKGROUND);
        log.info("[CODE-VIEW-SAVE] Project {} restored from its pre-apply snapshot", projectId);
        return storageManager.getPublicGraphVersion(projectId);
    }

    public long finishPatch(String projectId, String format, Path patchedFile, String userId, String username,
                            Model removed, Model added) throws IOException {
        return finishPatch(projectId, format, patchedFile, userId, username, removed, added, null);
    }

    public long finishPatch(String projectId, String format, Path patchedFile, String userId, String username,
                            Model removed, Model added, ChangeOrigin origin) throws IOException {
        invalidateReasonerCaches(projectId);
        ChangeOrigin resolved = origin != null ? origin : ChangeOrigin.manual();
        boolean recorded = false;
        try {
            historyRecorder.record(projectId, CodeViewHistoryRecorder.effectiveUserId(userId, desktopMode),
                    CodeViewHistoryRecorder.effectiveUsername(username), removed, added, false, resolved);
            recorded = true;
        } catch (Exception diffEx) {
            log.warn("[CODE-VIEW-SAVE] Failed to record change history for a patched apply: {}", diffEx.getMessage());
        }
        invalidateAfterGraphReplaced(projectId, MetadataRefresh.BACKGROUND);
        storageManager.storeCodeViewCacheFile(projectId, format, patchedFile);
        if (recorded) {
            announce(projectId, userId, username, resolved);
        }
        return storageManager.getPublicGraphVersion(projectId);
    }

    public void discardCodeViewCache(String projectId) {
        storageManager.clearCodeViewCache(projectId);
    }

    private void invalidateReasonerCaches(String projectId) {
        if (ontologyMutationService == null) {
            return;
        }
        try {
            ontologyMutationService.invalidateReasonerCaches(projectId);
        } catch (Exception cacheEx) {
            log.warn("[CODE-VIEW-SAVE] Failed busting reasoner caches for project {} (non-fatal): {}",
                    projectId, cacheEx.getMessage());
        }
    }

    private void invalidateAfterDraftReplaced(String projectId, String userId) {
        invalidateDerivedCaches(projectId);
        storageManager.bumpDraftGraphVersion(projectId, userId);
    }

    private void invalidateAfterGraphReplaced(String projectId, MetadataRefresh refresh) {
        invalidateDerivedCaches(projectId);
        storageManager.clearCodeViewCache(projectId);
        datasetService.evictPublicReadCache(projectId);
        if (indexService == null) {
            return;
        }
        long seq = metadataRefreshSeq.computeIfAbsent(projectId, id -> new AtomicLong()).incrementAndGet();
        if (refresh == MetadataRefresh.NOW || metadataExecutor == null) {
            refreshCachedMetadata(projectId, seq);
            return;
        }
        try {
            metadataExecutor.execute(() -> refreshCachedMetadata(projectId, seq));
        } catch (RejectedExecutionException rejected) {
            log.warn("[CODE-VIEW-SAVE] Metadata refresh queue is full; project {} keeps its previous counts until the next change",
                    projectId);
        }
    }

    private void refreshCachedMetadata(String projectId, long seq) {
        try {
            Map<String, Object> meta = indexService.computeMetadata(projectId);
            if (metadataRefreshSeq.get(projectId).get() == seq) {
                metadataService.writeMeta(projectId, meta);
            }
        } catch (Exception metaEx) {
            log.warn("[CODE-VIEW-SAVE] Failed to refresh cached metadata for project {}: {}",
                    projectId, metaEx.getMessage());
        }
    }

    private void invalidateDerivedCaches(String projectId) {
        datasetService.markProjectDirty(projectId);
        if (ontologyCache != null) {
            ontologyCache.evict(projectId);
            log.info("[CODE-VIEW-SAVE] Evicted in-memory OWLAPI cache for project {} (now stale vs. reimported Fuseki data)", projectId);
        }
        metadataService.incrementMutationVersion(projectId);

        if (hierarchyIndexService != null) {
            hierarchyIndexService.scheduleBuild(projectId);
        }

        if (ontologyQueryService != null) {
            ontologyQueryService.evictIndividualAndAnnotationPropertyCaches(projectId);
        }
        if (editorReasonerCache != null) {
            editorReasonerCache.invalidateOntology(projectId);
        }
    }

    private Path convertToRdfXml(Path sourceFile) throws IOException {
        try {
            return OWLFormatConverter.convertToRDFXML(sourceFile);
        } catch (org.semanticweb.owlapi.model.OWLOntologyCreationException | org.semanticweb.owlapi.model.OWLOntologyStorageException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private void streamIntoGraphDb(String projectId, Path file, RDFFormat rdfFormat, String targetGraphOverride) throws IOException {
        long size = Files.size(file);
        try (InputStream is = Files.newInputStream(file)) {
            datasetService.bulkLoadChunked(projectId, is, rdfFormat, size, ImportOptions.defaults(), null, targetGraphOverride);
        }
    }

    private Model parseToModel(Path file, RDFFormat format) throws IOException {
        Model model = new LinkedHashModel();
        RDFParser parser = Rio.createParser(format);
        parser.setRDFHandler(new StatementCollector(model));
        try (InputStream is = Files.newInputStream(file)) {
            parser.parse(is, "");
        }
        return model;
    }

    private boolean isXmlStructuralError(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("must be terminated") ||
                        lower.contains("end-tag") ||
                        lower.contains("end tag") ||
                        lower.contains("unexpected end of file") ||
                        lower.contains("premature end of file") ||
                        lower.contains("content is not allowed in prolog") ||
                        lower.contains("invalid xml") ||
                        lower.contains("invalid iri") ||
                        lower.contains("invalidvalueexception") ||
                        lower.contains("illegalstateexception") ||
                        lower.contains("illegal state")) {
                    return true;
                }
                if (current.getClass().getName().contains("SAXParseException")) {
                    boolean isNamespaceError = lower.contains("prefix")
                            && (lower.contains("bound") || lower.contains("not bound"));
                    if (!isNamespaceError) {
                        return true;
                    }
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
