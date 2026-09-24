package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class CodeViewReimportPipeline {

    private static final String DESKTOP_USER_ID = "desktop-user-local";
    private static final int BULK_DIFF_THRESHOLD = 60;

    private final StorageManager storageManager;
    private final SparqlDatasetService datasetService;
    private final ProjectMetadataService metadataService;
    private final OntologyHistoryService historyService;
    private final DraftTrackingService draftTrackingService;

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
        this.historyService = historyService;
        this.draftTrackingService = draftTrackingService;
    }

    public record ReimportRequest(String projectId, String format, Path contentFile, boolean draft,
                                   String userId, String username, String targetGraphOverride,
                                   Path oldContentFileForDiff, boolean skipSanitization) {}

    public record ReimportResult(String format, RDFFormat rdfFormat, long sourceVersion) {}

    public ReimportResult reimport(ReimportRequest req) throws IOException {
        String format = req.format();
        boolean isOwlApiFormat = format.equalsIgnoreCase("owlxml")
                || format.equalsIgnoreCase("manchester")
                || format.equalsIgnoreCase("manchestersyntax")
                || format.equalsIgnoreCase("functional")
                || format.equalsIgnoreCase("functionalsyntax");

        RDFFormat rdfFormat;
        Path importSourceFile;
        Path retrySourceFile;
        Path pristineCopy = null;
        Path generatedFile = null;

        try {
            if (isOwlApiFormat) {
                importSourceFile = convertToRdfXml(req.contentFile());
                generatedFile = importSourceFile;
                retrySourceFile = req.contentFile();
                rdfFormat = RDFFormat.RDFXML;
                log.info("[CODE-VIEW-SAVE] Converted {} to RDF/XML ({} bytes)", format, Files.size(importSourceFile));
            } else {
                pristineCopy = Files.createTempFile("codeview-pristine-", "." + storageManager.extensionFor(format));
                Files.copy(req.contentFile(), pristineCopy, StandardCopyOption.REPLACE_EXISTING);
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
                importSourceFile = req.contentFile();
                retrySourceFile = pristineCopy;
                rdfFormat = switch (format.toLowerCase(Locale.ROOT)) {
                    case "turtle", "ttl" -> RDFFormat.TURTLE;
                    case "ntriples", "nt" -> RDFFormat.NTRIPLES;
                    default -> RDFFormat.RDFXML;
                };
            }

            log.info("[CODE-VIEW-SAVE] Reimporting {} bytes into GraphDB as {} (draft={}, targetGraph={})",
                    Files.size(importSourceFile), rdfFormat, req.draft(), req.targetGraphOverride());
            try {
                streamIntoGraphDb(req.projectId(), importSourceFile, rdfFormat, req.targetGraphOverride());
            } catch (RuntimeException bulkEx) {
                if (rdfFormat == RDFFormat.RDFXML && isXmlStructuralError(bulkEx)) {
                    log.warn("[CODE-VIEW-SAVE] RDF/XML reimport failed with structural XML error; retrying after OWL API re-serialization for project: {}. Error: {}",
                            req.projectId(), bulkEx.getMessage());
                    Path retryConverted = convertToRdfXml(retrySourceFile);
                    if (generatedFile != null) {
                        Files.deleteIfExists(generatedFile);
                    }
                    importSourceFile = retryConverted;
                    generatedFile = retryConverted;
                    streamIntoGraphDb(req.projectId(), importSourceFile, RDFFormat.RDFXML, req.targetGraphOverride());
                    log.info("[CODE-VIEW-SAVE] OWL API re-serialization retry succeeded ({} bytes)", Files.size(importSourceFile));
                } else {
                    throw bulkEx;
                }
            }
            log.info("[CODE-VIEW-SAVE] GraphDB reimport complete");

            if (ontologyMutationService != null) {
                try {
                    ontologyMutationService.invalidateReasonerCaches(req.projectId());
                } catch (Exception cacheEx) {
                    log.warn("[CODE-VIEW-SAVE] Failed busting reasoner caches for project {} (non-fatal): {}",
                            req.projectId(), cacheEx.getMessage());
                }
            }

            if (req.oldContentFileForDiff() != null) {
                try {
                    String effectiveUserId = (req.userId() != null && !req.userId().isBlank()) ? req.userId() : "anonymous";
                    if (desktopMode) {
                        effectiveUserId = DESKTOP_USER_ID;
                    }
                    String effectiveUsername = (req.username() != null && !req.username().isBlank()) ? req.username() : "System";

                    Model oldModel = parseToModel(req.oldContentFileForDiff(), RDFFormat.RDFXML);
                    Model newModel = parseToModel(importSourceFile, rdfFormat);
                    recordOntologyDiff(req.projectId(), effectiveUserId, effectiveUsername, oldModel, newModel, req.draft());
                } catch (Exception diffEx) {
                    log.warn("[CODE-VIEW-SAVE] Failed to record change history diff (save itself succeeded): {}", diffEx.getMessage());
                }
            }

            invalidateAfterGraphReplaced(req.projectId());
            log.info("[CODE-VIEW-SAVE] All format caches cleared");

            String cachedContent = isOwlApiFormat
                    ? Files.readString(req.contentFile(), StandardCharsets.UTF_8)
                    : Files.readString(importSourceFile, StandardCharsets.UTF_8);
            storageManager.storeCodeViewCache(req.projectId(), cachedContent, format);
            log.info("[CODE-VIEW-SAVE] Current format cache restored");

            return new ReimportResult(format, rdfFormat, storageManager.getPublicGraphVersion(req.projectId()));
        } finally {
            if (generatedFile != null) {
                Files.deleteIfExists(generatedFile);
            }
            if (pristineCopy != null) {
                Files.deleteIfExists(pristineCopy);
            }
        }
    }

    public long restoreSnapshot(String projectId, Path rdfXmlSnapshot) throws IOException {
        if (rdfXmlSnapshot == null || !Files.isRegularFile(rdfXmlSnapshot)) {
            throw new IOException("The pre-apply snapshot is missing, so the project can't be restored from it");
        }
        log.warn("[CODE-VIEW-SAVE] Restoring project {} from pre-apply snapshot {} ({} bytes)",
                projectId, rdfXmlSnapshot.getFileName(), Files.size(rdfXmlSnapshot));
        streamIntoGraphDb(projectId, rdfXmlSnapshot, RDFFormat.RDFXML, null);
        if (ontologyMutationService != null) {
            try {
                ontologyMutationService.invalidateReasonerCaches(projectId);
            } catch (Exception cacheEx) {
                log.warn("[CODE-VIEW-SAVE] Failed busting reasoner caches for project {} after restore (non-fatal): {}",
                        projectId, cacheEx.getMessage());
            }
        }
        invalidateAfterGraphReplaced(projectId);
        log.info("[CODE-VIEW-SAVE] Project {} restored from its pre-apply snapshot", projectId);
        return storageManager.getPublicGraphVersion(projectId);
    }

    private void invalidateAfterGraphReplaced(String projectId) {
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

        storageManager.clearCodeViewCache(projectId);
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

    private boolean isNamedTriple(Statement st) {
        return st.getSubject() instanceof IRI
                && !(st.getObject() instanceof org.eclipse.rdf4j.model.BNode);
    }

    private String extractLocalName(String iri) {
        int idx = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return idx >= 0 && idx < iri.length() - 1 ? iri.substring(idx + 1) : iri;
    }

    private String findLabel(Model model, org.eclipse.rdf4j.model.Resource subject) {
        return model.filter(subject, RDFS.LABEL, null).stream()
                .findFirst()
                .map(st -> st.getObject().stringValue())
                .orElseGet(() -> extractLocalName(subject.stringValue()));
    }

    private void recordNamedStatementChange(String projectId, String userId, String username,
                                             Statement st, boolean isAddition, Model context,
                                             List<OntologyMutationService.MutationOp> draftOps) {
        String subjectIri = st.getSubject().stringValue();
        IRI predicate = st.getPredicate();
        String label = findLabel(context, st.getSubject());

        if (predicate.equals(RDF.TYPE) && st.getObject() instanceof IRI typeIri) {
            String opType = switch (typeIri.stringValue()) {
                case "http://www.w3.org/2002/07/owl#Class" -> isAddition ? "createClass" : "deleteClass";
                case "http://www.w3.org/2002/07/owl#ObjectProperty" -> isAddition ? "createObjectProperty" : "deleteObjectProperty";
                case "http://www.w3.org/2002/07/owl#DatatypeProperty" -> isAddition ? "createDataProperty" : "deleteDataProperty";
                case "http://www.w3.org/2002/07/owl#AnnotationProperty" -> isAddition ? "createAnnotationProperty" : "deleteAnnotationProperty";
                case "http://www.w3.org/2000/01/rdf-schema#Datatype" -> isAddition ? "createDatatype" : "deleteDatatype";
                case "http://www.w3.org/2002/07/owl#NamedIndividual" -> isAddition ? "createIndividual" : "deleteIndividual";
                default -> null;
            };
            if (opType != null) {
                historyService.recordEdit(projectId, userId, username, opType,
                        subjectIri, label, null, null,
                        opType + " operation via Code View", null);
                if (draftOps != null) {
                    draftOps.add(OntologyMutationService.MutationOp.forTypeAssertion(opType, subjectIri, label));
                }
            }
            return;
        }

        if (predicate.equals(RDFS.SUBCLASSOF) && st.getObject() instanceof IRI parentIri) {
            String opType = isAddition ? "addSubClassOf" : "removeSubClassOf";
            historyService.recordEdit(projectId, userId, username,
                    opType, subjectIri, label,
                    isAddition ? null : parentIri.stringValue(),
                    isAddition ? parentIri.stringValue() : null,
                    "subClassOf changed via Code View", null);
            if (draftOps != null) {
                draftOps.add(OntologyMutationService.MutationOp
                        .forSubClassOfChange(opType, subjectIri, label, parentIri.stringValue(), isAddition));
            }
            return;
        }

        if (predicate.equals(RDFS.LABEL) || predicate.equals(RDFS.COMMENT)) {
            String opType = isAddition ? "addAnnotation" : "removeAnnotation";
            historyService.recordEdit(projectId, userId, username,
                    opType, subjectIri, label, null, st.getObject().stringValue(),
                    "Annotation changed via Code View", predicate.stringValue());
            if (draftOps != null) {
                draftOps.add(OntologyMutationService.MutationOp
                        .forPropertyAssertion(opType, subjectIri, label, predicate.stringValue(), st.getObject().stringValue()));
            }
            return;
        }

        String opType = isAddition ? "addStatement" : "removeStatement";
        historyService.recordEdit(projectId, userId, username,
                opType, subjectIri, label,
                isAddition ? null : st.getObject().stringValue(),
                isAddition ? st.getObject().stringValue() : null,
                "Property assertion changed via Code View (" + predicate.stringValue() + ")",
                predicate.stringValue());
        if (draftOps != null) {
            draftOps.add(OntologyMutationService.MutationOp
                    .forPropertyAssertion(opType, subjectIri, label, predicate.stringValue(), st.getObject().stringValue()));
        }
    }

    private void recordOntologyDiff(String projectId, String userId, String username,
                                     Model oldModel, Model newModel, boolean draft) {
        Set<Statement> added = new LinkedHashSet<>(newModel);
        added.removeAll(oldModel);
        Set<Statement> removed = new LinkedHashSet<>(oldModel);
        removed.removeAll(newModel);

        int namedChangeCount = 0;
        for (Statement st : added) {
            if (isNamedTriple(st)) namedChangeCount++;
        }
        for (Statement st : removed) {
            if (isNamedTriple(st)) namedChangeCount++;
        }

        if (namedChangeCount > BULK_DIFF_THRESHOLD) {
            historyService.recordEdit(projectId, userId, username,
                    "bulkPopulation", null, null, null, null,
                    "Code View save added/changed " + namedChangeCount
                            + " statements — logged as a single bulk entry rather than one per statement",
                    null);
            log.info("[CODE-VIEW-SAVE] Skipped per-triple change logging for bulk save ({} named changes)", namedChangeCount);
            return;
        }

        List<OntologyMutationService.MutationOp> draftOps = draft ? new ArrayList<>() : null;

        int structuralChanges = 0;
        for (Statement st : added) {
            if (!isNamedTriple(st)) { structuralChanges++; continue; }
            recordNamedStatementChange(projectId, userId, username, st, true, newModel, draftOps);
        }
        for (Statement st : removed) {
            if (!isNamedTriple(st)) { structuralChanges++; continue; }
            recordNamedStatementChange(projectId, userId, username, st, false, oldModel, draftOps);
        }
        if (structuralChanges > 0) {
            historyService.recordEdit(projectId, userId, username,
                    "codeViewStructuralEdit", null, null, null, null,
                    "Code View save modified " + structuralChanges
                            + " structural axiom(s) (restrictions, unions, SWRL rules, disjoint-class lists, etc.)",
                    null);
        }

        if (draft && draftOps != null && !draftOps.isEmpty()) {
            String sessionId = UUID.randomUUID().toString();
            draftTrackingService.recordDrafts(projectId, userId, username, draftOps, sessionId);
        }
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
