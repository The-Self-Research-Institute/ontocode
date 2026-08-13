package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.OWLEntityRenamer;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import self.research.ontology.owlEditor.model.merge.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OntologyMergeService {

    private static final Logger log = LoggerFactory.getLogger(OntologyMergeService.class);

    private final SparqlDatasetService datasetService;
    private final ProjectImportService importService;
    private final StorageManager storageManager;
    private final ProjectMetadataService metadataService;

    @Value("${auth.service.url:http://localhost:8086}")
    private String authServiceUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public OntologyMergeService(SparqlDatasetService datasetService,
                               ProjectImportService importService,
                               StorageManager storageManager,
                               ProjectMetadataService metadataService) {
        this.datasetService = datasetService;
        this.importService = importService;
        this.storageManager = storageManager;
        this.metadataService = metadataService;
    }

    public MergeAnalysisResult analyzeOntologies(String sourceProjectId, Path sourceFile,
                                                  String targetProjectId) throws OWLOntologyCreationException {
        return analyzeOntologies(sourceProjectId, sourceFile, targetProjectId, null);
    }

    public MergeAnalysisResult analyzeOntologies(String sourceProjectId, Path sourceFile,
                                                  String targetProjectId,
                                                  String targetFileName) throws OWLOntologyCreationException {
        log.info("[MERGE] Analyzing ontologies for conflicts");
        log.info("[MERGE] Source: {}, Target project: {}", sourceFile, targetProjectId);

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();

        OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
            .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);

        OWLOntology loadedSourceOntology = manager.loadOntologyFromOntologyDocument(
            new FileDocumentSource(sourceFile.toFile()),
            config
        );
        OWLOntology sourceOntology = cloneAsAnonymousOntology(manager, loadedSourceOntology);
        manager.removeOntology(loadedSourceOntology);

        Path targetFile;
        try {
            targetFile = resolveTargetOntologyFile(targetProjectId, targetFileName);
        } catch (Exception e) {
            throw new OWLOntologyCreationException(e.getMessage(), e);
        }
        OWLOntology targetOntology = manager.loadOntologyFromOntologyDocument(
            new FileDocumentSource(targetFile.toFile()),
            config
        );

        MergeAnalysisResult result = new MergeAnalysisResult();

        Map<IRI, Set<OWLAnnotationAssertionAxiom>> sourceAnnotIndex = buildAnnotationIndex(sourceOntology);
        Map<IRI, Set<OWLAnnotationAssertionAxiom>> targetAnnotIndex = buildAnnotationIndex(targetOntology);

        Map<IRI, String> sourceLabelIndex = buildLabelIndex(sourceOntology);

        long analysisStart = System.nanoTime();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.concurrent.Future<?> classFuture = executor.submit(() ->
                detectClassConflicts(sourceOntology, targetOntology, sourceAnnotIndex, targetAnnotIndex, result)
            );
            java.util.concurrent.Future<?> propertyFuture = executor.submit(() ->
                detectPropertyConflicts(sourceOntology, targetOntology, sourceAnnotIndex, targetAnnotIndex, result)
            );
            java.util.concurrent.Future<?> annotPropFuture = executor.submit(() ->
                detectAnnotationPropertyConflicts(sourceOntology, targetOntology, sourceAnnotIndex, targetAnnotIndex, result)
            );
            java.util.concurrent.Future<?> individualFuture = executor.submit(() ->
                detectIndividualConflicts(sourceOntology, targetOntology, sourceAnnotIndex, targetAnnotIndex, result)
            );

            classFuture.get();
            propertyFuture.get();
            annotPropFuture.get();
            individualFuture.get();

            long parallelDuration = (System.nanoTime() - analysisStart) / 1_000_000;
            log.info("⚡ PERFORMANCE: Parallel conflict detection completed in {} ms", parallelDuration);

            detectAxiomConflicts(sourceOntology, targetOntology, result);

        } catch (Exception e) {
            log.error("[MERGE] Parallel conflict detection failed, falling back to sequential", e);

            detectClassConflicts(sourceOntology, targetOntology, sourceAnnotIndex, targetAnnotIndex, result);
            detectPropertyConflicts(sourceOntology, targetOntology, sourceAnnotIndex, targetAnnotIndex, result);
            detectAnnotationPropertyConflicts(sourceOntology, targetOntology, sourceAnnotIndex, targetAnnotIndex, result);
            detectIndividualConflicts(sourceOntology, targetOntology, sourceAnnotIndex, targetAnnotIndex, result);
            detectAxiomConflicts(sourceOntology, targetOntology, result);
        } finally {
            executor.shutdown();
        }

        result.setSourceClassCount((int) sourceOntology.getClassesInSignature().stream().filter(c -> !c.isBuiltIn()).count());
        result.setSourcePropertyCount((int) sourceOntology.getObjectPropertiesInSignature().size() +
                                      (int) sourceOntology.getDataPropertiesInSignature().size());
        result.setTargetClassCount((int) targetOntology.getClassesInSignature().stream().filter(c -> !c.isBuiltIn()).count());
        result.setTargetPropertyCount((int) targetOntology.getObjectPropertiesInSignature().size() +
                                      (int) targetOntology.getDataPropertiesInSignature().size());
        result.setSourceIndividualCount((int) sourceOntology.getIndividualsInSignature().size());
        result.setTargetIndividualCount((int) targetOntology.getIndividualsInSignature().size());

        Set<String> sourceClassIris = sourceOntology.getClassesInSignature().stream()
            .filter(c -> !c.isBuiltIn())
            .map(c -> c.getIRI().toString())
            .collect(Collectors.toSet());
        Set<String> targetClassIris = targetOntology.getClassesInSignature().stream()
            .filter(c -> !c.isBuiltIn())
            .map(c -> c.getIRI().toString())
            .collect(Collectors.toSet());
        Set<String> sourceOnlyClasses = new HashSet<>(sourceClassIris);
        sourceOnlyClasses.removeAll(targetClassIris);
        Set<String> targetOnlyClasses = new HashSet<>(targetClassIris);
        targetOnlyClasses.removeAll(sourceClassIris);
        result.setSourceOnlyClassCount(sourceOnlyClasses.size());
        result.setTargetOnlyClassCount(targetOnlyClasses.size());
        result.setSourceOnlyClasses(sourceOnlyClasses.stream().sorted().limit(100).collect(Collectors.toList()));
        Map<String, String> sourceOnlyClassLabels = new HashMap<>();
        sourceOnlyClasses.forEach(iri -> sourceOnlyClassLabels.put(iri, lookupLabel(sourceLabelIndex, IRI.create(iri))));
        result.setSourceOnlyClassLabels(sourceOnlyClassLabels);

        Set<String> sourcePropertyIris = new HashSet<>();
        sourceOntology.getObjectPropertiesInSignature().forEach(p -> sourcePropertyIris.add(p.getIRI().toString()));
        sourceOntology.getDataPropertiesInSignature().forEach(p -> sourcePropertyIris.add(p.getIRI().toString()));
        Set<String> targetPropertyIris = new HashSet<>();
        targetOntology.getObjectPropertiesInSignature().forEach(p -> targetPropertyIris.add(p.getIRI().toString()));
        targetOntology.getDataPropertiesInSignature().forEach(p -> targetPropertyIris.add(p.getIRI().toString()));
        Set<String> sourceOnlyProperties = new HashSet<>(sourcePropertyIris);
        sourceOnlyProperties.removeAll(targetPropertyIris);
        Set<String> targetOnlyProperties = new HashSet<>(targetPropertyIris);
        targetOnlyProperties.removeAll(sourcePropertyIris);
        result.setSourceOnlyPropertyCount(sourceOnlyProperties.size());
        result.setTargetOnlyPropertyCount(targetOnlyProperties.size());
        result.setSourceOnlyProperties(sourceOnlyProperties.stream().sorted().limit(100).collect(Collectors.toList()));
        Map<String, String> sourceOnlyPropertyLabels = new HashMap<>();
        sourceOnlyProperties.forEach(iri -> sourceOnlyPropertyLabels.put(iri, lookupLabel(sourceLabelIndex, IRI.create(iri))));
        result.setSourceOnlyPropertyLabels(sourceOnlyPropertyLabels);

        Set<String> sourceIndividualIris = sourceOntology.getIndividualsInSignature().stream()
            .map(i -> i.getIRI().toString())
            .collect(Collectors.toSet());
        Set<String> targetIndividualIris = targetOntology.getIndividualsInSignature().stream()
            .map(i -> i.getIRI().toString())
            .collect(Collectors.toSet());
        Set<String> sourceOnlyIndividuals = new HashSet<>(sourceIndividualIris);
        sourceOnlyIndividuals.removeAll(targetIndividualIris);
        Set<String> targetOnlyIndividuals = new HashSet<>(targetIndividualIris);
        targetOnlyIndividuals.removeAll(sourceIndividualIris);
        result.setSourceOnlyIndividualCount(sourceOnlyIndividuals.size());
        result.setTargetOnlyIndividualCount(targetOnlyIndividuals.size());
        result.setSourceOnlyIndividuals(sourceOnlyIndividuals.stream().sorted().limit(100).collect(Collectors.toList()));
        Map<String, String> sourceOnlyIndividualLabels = new HashMap<>();
        sourceOnlyIndividuals.forEach(iri -> sourceOnlyIndividualLabels.put(iri, lookupLabel(sourceLabelIndex, IRI.create(iri))));
        result.setSourceOnlyIndividualLabels(sourceOnlyIndividualLabels);

        Set<OWLAxiom> targetAxiomSet = new HashSet<>(targetOntology.getAxioms());
        int sourceOnlyAxiomCount = 0;
        for (OWLAxiom ax : sourceOntology.getAxioms()) {
            if (!targetAxiomSet.contains(ax)) sourceOnlyAxiomCount++;
        }
        Set<OWLAxiom> sourceAxiomSet = new HashSet<>(sourceOntology.getAxioms());
        int targetOnlyAxiomCount = 0;
        for (OWLAxiom ax : targetOntology.getAxioms()) {
            if (!sourceAxiomSet.contains(ax)) targetOnlyAxiomCount++;
        }
        result.setSourceOnlyAxiomCount(sourceOnlyAxiomCount);
        result.setTargetOnlyAxiomCount(targetOnlyAxiomCount);

        if (sourceOnlyAxiomCount == 0 && targetOnlyAxiomCount == 0) {
            log.info("[MERGE] Duplicate file detected: source ontology is identical to target ontology");
            MergeConflict duplicateConflict = new MergeConflict();
            duplicateConflict.setEntityIRI("ontology://duplicate-file");
            duplicateConflict.setEntityType("Ontology");
            duplicateConflict.setConflictType(ConflictType.IDENTICAL_FILE_UPLOAD);
            duplicateConflict.setSeverity(ConflictSeverity.HIGH);
            duplicateConflict.setDescription(
                "The uploaded ontology is identical to the existing one. " +
                "This appears to be a duplicate upload of the same file. " +
                "No new content will be added if you proceed with the merge."
            );
            duplicateConflict.setSourceDefinition("Identical ontology (0 new axioms)");
            duplicateConflict.setTargetDefinition("Existing ontology");
            result.addConflict(duplicateConflict);
        }

        int totalSourceAxioms = sourceOntology.getAxiomCount();
        if (totalSourceAxioms > 0) {
            double differencePercentage = ((double) sourceOnlyAxiomCount / totalSourceAxioms) * 100.0;
            if (differencePercentage < 1.0 && sourceOnlyAxiomCount > 0) {
                log.info("[MERGE] Near-duplicate file detected: {}% of axioms are different", String.format("%.2f", differencePercentage));
                MergeConflict nearDuplicateConflict = new MergeConflict();
                nearDuplicateConflict.setEntityIRI("ontology://near-duplicate-file");
                nearDuplicateConflict.setEntityType("Ontology");
                nearDuplicateConflict.setConflictType(ConflictType.DUPLICATE_FILE_CONTENT);
                nearDuplicateConflict.setSeverity(ConflictSeverity.MEDIUM);
                nearDuplicateConflict.setDescription(
                    String.format(
                        "The uploaded ontology is nearly identical to the existing one (%.2f%% difference). " +
                        "This may be a duplicate upload with only minor changes. " +
                        "Review the differences before proceeding.",
                        differencePercentage
                    )
                );
                nearDuplicateConflict.setSourceDefinition(sourceOnlyAxiomCount + " new axioms detected");
                nearDuplicateConflict.setTargetDefinition(totalSourceAxioms + " total axioms in source");
                result.addConflict(nearDuplicateConflict);
            }
        }

        result.setClassHierarchy(buildClassHierarchy(sourceOntology, targetOntology));
        result.setPropertyHierarchy(buildPropertyHierarchy(sourceOntology, targetOntology));

        log.info("[MERGE] Analysis complete: {} conflicts detected", result.getTotalConflicts());

        manager.removeOntology(sourceOntology);
        manager.removeOntology(targetOntology);

        return result;
    }

    public MergeResult mergeOntologies(String sourceProjectId, Path sourceFile,
                                      String targetProjectId,
                                      String targetFileName,
                                      String outputFileName,
                                      MergeOptions options) throws Exception {
        log.info("[MERGE] Starting merge operation");
        log.info("[MERGE] Source: {}, Target: {}", sourceFile, targetProjectId);
        log.info("[MERGE] Strategy: {}", options.getStrategy());

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();

        OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
            .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);

        OWLOntology loadedSourceOntology = manager.loadOntologyFromOntologyDocument(
            new FileDocumentSource(sourceFile.toFile()),
            config
        );
        OWLOntology sourceOntology = cloneAsAnonymousOntology(manager, loadedSourceOntology);
        manager.removeOntology(loadedSourceOntology);
        Path targetFile = resolveTargetOntologyFile(targetProjectId, targetFileName);
        OWLOntology targetOntology = manager.loadOntologyFromOntologyDocument(
            new FileDocumentSource(targetFile.toFile()),
            config
        );

        MergeResult result = new MergeResult();
        result.setTargetProjectId(targetProjectId);

        try {
            switch (options.getStrategy()) {
                case SIMPLE_UNION:
                    performSimpleUnion(sourceOntology, targetOntology, manager, result);
                    break;

                case REPLACE_DUPLICATES:
                    performReplaceDuplicates(sourceOntology, targetOntology, manager, options, result);
                    break;

                case KEEP_BOTH:
                    performKeepBoth(sourceOntology, targetOntology, manager, options, result);
                    break;

                case MANUAL_RESOLUTION:
                    performManualResolution(sourceOntology, targetOntology, manager, options, result);
                    break;

                default:
                    throw new IllegalArgumentException("Unknown merge strategy: " + options.getStrategy());
            }

            Path outputPath;
            String importProjectId = targetProjectId;
            boolean isNewFile = outputFileName != null && !outputFileName.isBlank();

            if (isNewFile) {

                String tempProjectId = "merge-temp-" + java.util.UUID.randomUUID();
                log.info("[MERGE] Saving merged output to temp project '{}' for file '{}'", tempProjectId, outputFileName);

                try {
                    storageManager.prepareProjectDir(tempProjectId);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to create temp directory for merge output: " + tempProjectId, e);
                }

                outputPath = storageManager.resolveProjectFile(tempProjectId, "ontology.current.owl");
                result.setTargetProjectId(tempProjectId);
            } else {
                outputPath = targetFile;
            }

            File outputFile = outputPath.toFile();
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                manager.saveOntology(targetOntology, fos);
            }

            if (!isNewFile) {

                refreshMergedMetadata(importProjectId, targetOntology);
                updateGridFsFile(importProjectId, targetFileName, outputPath);
                importService.submitImport(importProjectId, outputPath);
            }

            result.setSuccess(true);
            result.setMessage("Merge completed successfully");

            log.info("[MERGE] Merge operation completed successfully");

        } catch (Exception e) {
            log.error("[MERGE] Merge operation failed", e);
            result.setSuccess(false);
            result.setMessage("Merge failed: " + e.getMessage());
            throw e;
        } finally {

            try {
                manager.removeOntology(sourceOntology);
                manager.removeOntology(targetOntology);
            } catch (Exception e) {
                log.debug("[MERGE] Error cleaning up OWL manager: {}", e.getMessage());
            }
        }

        return result;
    }

    private void refreshMergedMetadata(String projectId, OWLOntology ontology) {
        try {
            Map<String, Object> meta = metadataService.readMeta(projectId).orElseGet(HashMap::new);
            meta.put("classCount", (int) ontology.getClassesInSignature().stream().filter(c -> !c.isBuiltIn()).count());
            meta.put("objectPropertyCount", ontology.getObjectPropertiesInSignature().size());
            meta.put("dataPropertyCount", ontology.getDataPropertiesInSignature().size());
            meta.put("annotationPropertyCount", ontology.getAnnotationPropertiesInSignature().size());
            meta.put("individualCount", ontology.getIndividualsInSignature().size());
            meta.put("propertyCount",
                ontology.getObjectPropertiesInSignature().size() + ontology.getDataPropertiesInSignature().size());
            meta.put("axiomCount", ontology.getAxiomCount());
            metadataService.writeMeta(projectId, meta);
        } catch (Exception e) {
            log.warn("[MERGE] Failed to refresh metadata after merge for {}: {}", projectId, e.getMessage());
        }
    }

    private void updateGridFsFile(String projectId, String fileName, Path mergedFilePath) {
        try {
            log.info("[MERGE] Updating GridFS file for project {} file {}", projectId, fileName);

            String getFilesUrl = authServiceUrl + "/api/projects/" + projectId + "/files";
            ResponseEntity<Map<String, Object>> filesResponse = restTemplate.exchange(
                getFilesUrl,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {}
            );

            if (!filesResponse.getStatusCode().is2xxSuccessful() || filesResponse.getBody() == null) {
                log.warn("[MERGE] Failed to get files for project {}: {}", projectId, filesResponse.getStatusCode());
                return;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) filesResponse.getBody().get("files");
            if (files == null) {
                log.warn("[MERGE] No files found for project {}", projectId);
                return;
            }

            String targetFileId = null;
            for (Map<String, Object> file : files) {
                String fileNameInList = (String) file.get("name");
                if (fileName.equals(fileNameInList)) {
                    targetFileId = (String) file.get("id");
                    break;
                }
            }

            if (targetFileId == null) {
                log.warn("[MERGE] Target file {} not found in project {} files", fileName, projectId);
                return;
            }

            log.info("[MERGE] Found target file ID: {} for file {}", targetFileId, fileName);

            long fileSizeBytes = Files.size(mergedFilePath);
            String updateFileUrl = authServiceUrl + "/api/projects/" + projectId + "/files";

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(mergedFilePath.toFile()) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            });
            body.add("fileName", fileName);
            body.add("replaceFileId", targetFileId);
            body.add("fileType", "application/rdf+xml");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<Map<String, Object>> updateResponse = gridFsRestTemplate().exchange(
                updateFileUrl,
                HttpMethod.POST,
                requestEntity,
                new ParameterizedTypeReference<Map<String, Object>>() {}
            );

            if (updateResponse.getStatusCode().is2xxSuccessful()) {
                log.info("[MERGE] Successfully updated GridFS file {} for project {} ({} MB)",
                        targetFileId, projectId, fileSizeBytes / (1024 * 1024));
            } else {
                log.error("[MERGE] Failed to update GridFS file {} for project {}: {} — "
                        + "library/download via auth may serve a stale file; editor disk copy is authoritative",
                    targetFileId, projectId, updateResponse.getStatusCode());
            }

        } catch (Exception e) {
            log.error("[MERGE] Failed to update GridFS file for project {} file {}: {} — "
                    + "editor disk copy is still correct; re-upload from project library may import a stale GridFS object",
                projectId, fileName, e.getMessage(), e);
        }
    }

    private RestTemplate gridFsRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(60_000);

        factory.setReadTimeout(3 * 60 * 60 * 1000);
        return new RestTemplate(factory);
    }

    private Path resolveTargetOntologyFile(String targetProjectId, String targetFileName) throws Exception {
        if (targetFileName != null && !targetFileName.isBlank()) {
            Path namedTargetPath = storageManager.resolveProjectFile(targetProjectId, targetFileName);
            if (Files.exists(namedTargetPath)) {
                return namedTargetPath;
            }

            log.warn("[MERGE] Target file '{}' not found in local storage for project '{}'", targetFileName, targetProjectId);

            Optional<String> activeFilename = metadataService.readStatus(targetProjectId)
                .map(status -> status.filename())
                .filter(name -> name != null && !name.isBlank());

            if (activeFilename.isPresent() && activeFilename.get().equalsIgnoreCase(targetFileName)) {
                Optional<Path> currentOntology = storageManager.findCurrentOntology(targetProjectId);
                if (currentOntology.isPresent()) {
                    log.info("[MERGE] Resolved target '{}' to current ontology file '{}'", targetFileName, currentOntology.get());
                    return currentOntology.get();
                }
            }
        }

        Optional<Path> currentOntology = storageManager.findCurrentOntology(targetProjectId);
        if (currentOntology.isPresent()) {
            return currentOntology.get();
        }

        try {
            Path exported = storageManager.exportOntology(targetProjectId, "rdfxml");
            if (Files.exists(exported)) {
                log.info("[MERGE] Materialized target ontology from GraphDB for project '{}': {}", targetProjectId, exported);
                return exported;
            }
        } catch (Exception exportEx) {
            log.warn("[MERGE] Could not materialize target ontology from GraphDB for project '{}': {}", targetProjectId, exportEx.getMessage());
        }

        if (targetFileName != null && !targetFileName.isBlank()) {
            throw new Exception("Selected target file not found in project storage: " + targetFileName
                + ". No current ontology file or GraphDB export available for project: " + targetProjectId);
        }

        throw new Exception("Target ontology not found for project: " + targetProjectId);
    }

    private void performSimpleUnion(OWLOntology source, OWLOntology target,
                                   OWLOntologyManager manager, MergeResult result) throws OWLOntologyCreationException {
        log.info("[MERGE] Performing simple union merge (direct axiom transfer)");

        int addedCount = 0;

        for (OWLAxiom axiom : source.getAxioms()) {
            if (!target.containsAxiom(axiom)) {
                manager.addAxiom(target, axiom);
                addedCount++;
            }
        }

        for (OWLAnnotation annotation : source.getAnnotations()) {
            if (!target.getAnnotations().contains(annotation)) {
                manager.applyChange(new AddOntologyAnnotation(target, annotation));
            }
        }

        for (OWLImportsDeclaration importDecl : source.getImportsDeclarations()) {
            if (!target.getImportsDeclarations().contains(importDecl)) {
                manager.applyChange(new AddImport(target, importDecl));
            }
        }

        result.setAxiomsAdded(addedCount);
        log.info("[MERGE] Added {} new axioms via simple union", addedCount);
    }

    private void performReplaceDuplicates(OWLOntology source, OWLOntology target,
                                         OWLOntologyManager manager, MergeOptions options,
                                         MergeResult result) {
        log.info("[MERGE] Performing replace duplicates merge");

        Set<IRI> sourceIRIs = new HashSet<>();
        for (OWLEntity e : source.getSignature()) {
            sourceIRIs.add(e.getIRI());
        }

        Set<IRI> sharedIRIs = new HashSet<>();
        for (OWLEntity entity : source.getSignature()) {
            if (target.containsEntityInSignature(entity)) {
                sharedIRIs.add(entity.getIRI());
            }
        }
        log.info("[MERGE] Found {} shared entities — source will replace target definitions", sharedIRIs.size());

        Set<IRI> entitiesToFullyRemove = new HashSet<>();
        Set<OWLAxiom> sourceAxiomSet = new HashSet<>(source.getAxioms());

        for (OWLSubClassOfAxiom ax : target.getAxioms(AxiomType.SUBCLASS_OF)) {
            if (ax.getSuperClass().isAnonymous() || ax.getSubClass().isAnonymous()) continue;
            IRI superIRI = ax.getSuperClass().asOWLClass().getIRI();
            IRI subIRI = ax.getSubClass().asOWLClass().getIRI();
            if (sharedIRIs.contains(superIRI) && !sourceIRIs.contains(subIRI)) {
                entitiesToFullyRemove.add(subIRI);
            }
        }

        for (OWLSubObjectPropertyOfAxiom ax : target.getAxioms(AxiomType.SUB_OBJECT_PROPERTY)) {
            if (ax.getSuperProperty().isAnonymous() || ax.getSubProperty().isAnonymous()) continue;
            IRI superIRI = ax.getSuperProperty().asOWLObjectProperty().getIRI();
            IRI subIRI = ax.getSubProperty().asOWLObjectProperty().getIRI();
            if (sharedIRIs.contains(superIRI) && !sourceIRIs.contains(subIRI)) {
                entitiesToFullyRemove.add(subIRI);
            }
        }

        for (OWLSubDataPropertyOfAxiom ax : target.getAxioms(AxiomType.SUB_DATA_PROPERTY)) {
            IRI superIRI = ax.getSuperProperty().asOWLDataProperty().getIRI();
            IRI subIRI = ax.getSubProperty().asOWLDataProperty().getIRI();
            if (sharedIRIs.contains(superIRI) && !sourceIRIs.contains(subIRI)) {
                entitiesToFullyRemove.add(subIRI);
            }
        }

        for (OWLClassAssertionAxiom ax : target.getAxioms(AxiomType.CLASS_ASSERTION)) {
            if (ax.getClassExpression().isAnonymous()) continue;
            IRI classIRI = ax.getClassExpression().asOWLClass().getIRI();
            if (!ax.getIndividual().isAnonymous()) {
                IRI indIRI = ax.getIndividual().asOWLNamedIndividual().getIRI();
                if (sharedIRIs.contains(classIRI) && !sourceIRIs.contains(indIRI)) {
                    entitiesToFullyRemove.add(indIRI);
                }
            }
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (OWLSubClassOfAxiom ax : target.getAxioms(AxiomType.SUBCLASS_OF)) {
                if (ax.getSuperClass().isAnonymous() || ax.getSubClass().isAnonymous()) continue;
                IRI superIRI = ax.getSuperClass().asOWLClass().getIRI();
                IRI subIRI = ax.getSubClass().asOWLClass().getIRI();
                if (entitiesToFullyRemove.contains(superIRI) && !sourceIRIs.contains(subIRI)
                        && !entitiesToFullyRemove.contains(subIRI)) {
                    entitiesToFullyRemove.add(subIRI);
                    changed = true;
                }
            }
            for (OWLSubObjectPropertyOfAxiom ax : target.getAxioms(AxiomType.SUB_OBJECT_PROPERTY)) {
                if (ax.getSuperProperty().isAnonymous() || ax.getSubProperty().isAnonymous()) continue;
                IRI superIRI = ax.getSuperProperty().asOWLObjectProperty().getIRI();
                IRI subIRI = ax.getSubProperty().asOWLObjectProperty().getIRI();
                if (entitiesToFullyRemove.contains(superIRI) && !sourceIRIs.contains(subIRI)
                        && !entitiesToFullyRemove.contains(subIRI)) {
                    entitiesToFullyRemove.add(subIRI);
                    changed = true;
                }
            }
        }

        log.info("[MERGE] Will fully remove {} target-only entities (children of shared parents)", entitiesToFullyRemove.size());

        Set<OWLAxiom> toRemove = new HashSet<>();

        for (IRI iri : sharedIRIs) {
            toRemove.addAll(getDefiningAxioms(target, iri));
        }

        for (IRI iri : entitiesToFullyRemove) {
            toRemove.addAll(getAllAxiomsForIRI(target, iri));
        }

        for (OWLSubClassOfAxiom ax : target.getAxioms(AxiomType.SUBCLASS_OF)) {
            if (ax.getSuperClass().isAnonymous()) continue;
            IRI superIRI = ax.getSuperClass().asOWLClass().getIRI();
            if (sharedIRIs.contains(superIRI) && !sourceAxiomSet.contains(ax)) {
                toRemove.add(ax);
            }
        }
        for (OWLSubObjectPropertyOfAxiom ax : target.getAxioms(AxiomType.SUB_OBJECT_PROPERTY)) {
            if (ax.getSuperProperty().isAnonymous()) continue;
            IRI superIRI = ax.getSuperProperty().asOWLObjectProperty().getIRI();
            if (sharedIRIs.contains(superIRI) && !sourceAxiomSet.contains(ax)) {
                toRemove.add(ax);
            }
        }
        for (OWLSubDataPropertyOfAxiom ax : target.getAxioms(AxiomType.SUB_DATA_PROPERTY)) {
            IRI superIRI = ax.getSuperProperty().asOWLDataProperty().getIRI();
            if (sharedIRIs.contains(superIRI) && !sourceAxiomSet.contains(ax)) {
                toRemove.add(ax);
            }
        }
        for (OWLSubAnnotationPropertyOfAxiom ax : target.getAxioms(AxiomType.SUB_ANNOTATION_PROPERTY_OF)) {
            IRI superIRI = ax.getSuperProperty().getIRI();
            if (sharedIRIs.contains(superIRI) && !sourceAxiomSet.contains(ax)) {
                toRemove.add(ax);
            }
        }
        for (OWLClassAssertionAxiom ax : target.getAxioms(AxiomType.CLASS_ASSERTION)) {
            if (ax.getClassExpression().isAnonymous()) continue;
            IRI classIRI = ax.getClassExpression().asOWLClass().getIRI();
            if (sharedIRIs.contains(classIRI) && !sourceAxiomSet.contains(ax)) {
                toRemove.add(ax);
            }
        }

        log.info("[MERGE] Collected {} target axioms to remove", toRemove.size());

        if (!toRemove.isEmpty()) {
            List<OWLOntologyChange> removals = new ArrayList<>();
            for (OWLAxiom axiom : toRemove) {
                removals.add(new RemoveAxiom(target, axiom));
            }
            manager.applyChanges(removals);
        }

        int addedCount = 0;
        for (OWLAxiom axiom : source.getAxioms()) {
            if (!target.containsAxiom(axiom)) {
                manager.addAxiom(target, axiom);
                addedCount++;
            }
        }

        for (OWLAnnotation annotation : source.getAnnotations()) {
            if (!target.getAnnotations().contains(annotation)) {
                manager.applyChange(new AddOntologyAnnotation(target, annotation));
            }
        }

        for (OWLImportsDeclaration importDecl : source.getImportsDeclarations()) {
            if (!target.getImportsDeclarations().contains(importDecl)) {
                manager.applyChange(new AddImport(target, importDecl));
            }
        }

        result.setAxiomsAdded(addedCount);
        result.setAxiomsReplaced(sharedIRIs.size());
        result.setAxiomsRemoved(toRemove.size());
        log.info("[MERGE] Replaced {} entities, removed {} target-only entities, added {} new axioms",
                sharedIRIs.size(), entitiesToFullyRemove.size(), addedCount);
    }

    private void performKeepBoth(OWLOntology source, OWLOntology target,
                                OWLOntologyManager manager, MergeOptions options,
                                MergeResult result) {
        log.info("[MERGE] Performing keep both merge with renaming using OWLEntityRenamer");

        String renameSuffix = options.getRenameSuffix() != null ? options.getRenameSuffix() : "_imported";

        Map<OWLEntity, IRI> renameMap = new HashMap<>();
        Set<OWLEntity> sourceEntities = source.getSignature();

        for (OWLEntity entity : sourceEntities) {
            if (target.containsEntityInSignature(entity)) {
                IRI newIRI = IRI.create(entity.getIRI().toString() + renameSuffix);
                renameMap.put(entity, newIRI);
                log.debug("[MERGE] Will rename {} to {}", entity.getIRI(), newIRI);
            }
        }

        OWLEntityRenamer renamer = new OWLEntityRenamer(manager, Collections.singleton(source));
        List<OWLOntologyChange> changes = renamer.changeIRI(renameMap);

        manager.applyChanges(changes);
        log.info("[MERGE] Applied {} renaming changes", changes.size());

        int addedCount = 0;
        for (OWLAxiom axiom : source.getAxioms()) {
            manager.addAxiom(target, axiom);
            addedCount++;
        }

        for (OWLAnnotation annotation : source.getAnnotations()) {
            if (!target.getAnnotations().contains(annotation)) {
                manager.applyChange(new AddOntologyAnnotation(target, annotation));
            }
        }

        for (OWLImportsDeclaration importDecl : source.getImportsDeclarations()) {
            if (!target.getImportsDeclarations().contains(importDecl)) {
                manager.applyChange(new AddImport(target, importDecl));
            }
        }

        result.setEntitiesRenamed(renameMap.size());
        result.setAxiomsAdded(addedCount);
        log.info("[MERGE] Renamed {} entities, added {} axioms", renameMap.size(), addedCount);
    }

    private void performManualResolution(OWLOntology source, OWLOntology target,
                                        OWLOntologyManager manager, MergeOptions options,
                                        MergeResult result) throws OWLOntologyCreationException {
        log.info("[MERGE] Performing merge with manual conflict resolution");

        Map<String, ConflictResolution> resolutions = options.getConflictResolutions();
        if (resolutions == null || resolutions.isEmpty()) {
            log.warn("[MERGE] No conflict resolutions provided, defaulting to simple union");
            performSimpleUnion(source, target, manager, result);
            return;
        }

        Map<String, ConflictResolution> normalised = new LinkedHashMap<>();
        Set<String> entityIRIs = new HashSet<>();
        for (Map.Entry<String, ConflictResolution> entry : resolutions.entrySet()) {
            String rawKey = entry.getKey();
            String entityIRI = rawKey.contains("::") ? rawKey.substring(0, rawKey.indexOf("::")) : rawKey;
            normalised.put(entityIRI, entry.getValue());
            entityIRIs.add(entityIRI);
        }

        int resolvedCount = 0;
        OWLDataFactory factory = manager.getOWLDataFactory();

        for (Map.Entry<String, ConflictResolution> entry : normalised.entrySet()) {
            String entityIRI = entry.getKey();
            ConflictResolution resolution = entry.getValue();

            IRI iri = IRI.create(entityIRI);

            switch (resolution.getAction()) {
                case KEEP_SOURCE:

                    removeDefiningAxioms(target, iri, manager);
                    addDefiningAxioms(source, target, iri, manager);
                    resolvedCount++;
                    break;

                case KEEP_TARGET:

                    resolvedCount++;
                    break;

                case RENAME_SOURCE:

                    String suffix = resolution.getRenameSuffix() != null ?
                                  resolution.getRenameSuffix() : "_imported";
                    addEntityWithRename(source, target, iri, suffix, manager, factory);
                    resolvedCount++;
                    break;

                case MERGE:

                    addDefiningAxioms(source, target, iri, manager);
                    resolvedCount++;
                    break;

                case SKIP:

                    resolvedCount++;
                    break;
            }
        }

        int addedCount = 0;
        for (OWLAxiom axiom : source.getAxioms()) {
            if (!isConflictingAxiom(axiom, entityIRIs)) {
                if (!target.containsAxiom(axiom)) {
                    manager.addAxiom(target, axiom);
                    addedCount++;
                }
            }
        }

        for (OWLAnnotation annotation : source.getAnnotations()) {
            if (!target.getAnnotations().contains(annotation)) {
                manager.applyChange(new AddOntologyAnnotation(target, annotation));
            }
        }

        for (OWLImportsDeclaration importDecl : source.getImportsDeclarations()) {
            if (!target.getImportsDeclarations().contains(importDecl)) {
                manager.applyChange(new AddImport(target, importDecl));
            }
        }

        result.setConflictsResolved(resolvedCount);
        result.setAxiomsAdded(addedCount);
        log.info("[MERGE] Resolved {} conflicts, added {} new axioms", resolvedCount, addedCount);
    }

    private void detectClassConflicts(OWLOntology source, OWLOntology target,
                                     Map<IRI, Set<OWLAnnotationAssertionAxiom>> srcAnnot,
                                     Map<IRI, Set<OWLAnnotationAssertionAxiom>> tgtAnnot,
                                     MergeAnalysisResult result) {
        Set<OWLClass> sourceClasses = source.getClassesInSignature().stream()
                                            .filter(c -> !c.isBuiltIn())
                                            .collect(Collectors.toSet());
        Set<OWLClass> targetClasses = target.getClassesInSignature();

        for (OWLClass cls : sourceClasses) {
            if (targetClasses.contains(cls)) {
                Set<OWLClassAxiom> sourceAxioms = source.getAxioms(cls);
                Set<OWLClassAxiom> targetAxioms = target.getAxioms(cls);
                Set<OWLAnnotationAssertionAxiom> sourceAnnotations = srcAnnot.getOrDefault(cls.getIRI(), Collections.emptySet());
                Set<OWLAnnotationAssertionAxiom> targetAnnotations = tgtAnnot.getOrDefault(cls.getIRI(), Collections.emptySet());

                if (!sourceAxioms.equals(targetAxioms) || !sourceAnnotations.equals(targetAnnotations)) {
                    MergeConflict conflict = new MergeConflict();
                    conflict.setEntityIRI(cls.getIRI().toString());
                    conflict.setEntityType("Class");
                    conflict.setConflictType(ConflictType.DIFFERENT_DEFINITION);
                    Set<OWLAxiom> allSource = new HashSet<>(sourceAxioms);
                    allSource.addAll(sourceAnnotations);
                    Set<OWLAxiom> allTarget = new HashSet<>(targetAxioms);
                    allTarget.addAll(targetAnnotations);
                    conflict.setSourceDefinition(formatAxioms(allSource));
                    conflict.setTargetDefinition(formatAxioms(allTarget));
                    result.addConflict(conflict);
                }
            }
        }
    }

    private void detectPropertyConflicts(OWLOntology source, OWLOntology target,
                                        Map<IRI, Set<OWLAnnotationAssertionAxiom>> srcAnnot,
                                        Map<IRI, Set<OWLAnnotationAssertionAxiom>> tgtAnnot,
                                        MergeAnalysisResult result) {

        Set<OWLObjectProperty> sourceObjProps = source.getObjectPropertiesInSignature();
        Set<OWLObjectProperty> targetObjProps = target.getObjectPropertiesInSignature();

        for (OWLObjectProperty prop : sourceObjProps) {
            if (targetObjProps.contains(prop)) {
                Set<OWLObjectPropertyAxiom> sourceAxioms = source.getAxioms(prop);
                Set<OWLObjectPropertyAxiom> targetAxioms = target.getAxioms(prop);
                Set<OWLAnnotationAssertionAxiom> sourceAnnotations = srcAnnot.getOrDefault(prop.getIRI(), Collections.emptySet());
                Set<OWLAnnotationAssertionAxiom> targetAnnotations = tgtAnnot.getOrDefault(prop.getIRI(), Collections.emptySet());

                if (!sourceAxioms.equals(targetAxioms) || !sourceAnnotations.equals(targetAnnotations)) {
                    MergeConflict conflict = new MergeConflict();
                    conflict.setEntityIRI(prop.getIRI().toString());
                    conflict.setEntityType("ObjectProperty");
                    conflict.setConflictType(ConflictType.DIFFERENT_DEFINITION);
                    Set<OWLAxiom> allSource = new HashSet<>(sourceAxioms);
                    allSource.addAll(sourceAnnotations);
                    Set<OWLAxiom> allTarget = new HashSet<>(targetAxioms);
                    allTarget.addAll(targetAnnotations);
                    conflict.setSourceDefinition(formatAxioms(allSource));
                    conflict.setTargetDefinition(formatAxioms(allTarget));
                    result.addConflict(conflict);
                }
            }
        }

        Set<OWLDataProperty> sourceDataProps = source.getDataPropertiesInSignature();
        Set<OWLDataProperty> targetDataProps = target.getDataPropertiesInSignature();

        for (OWLDataProperty prop : sourceDataProps) {
            if (targetDataProps.contains(prop)) {
                Set<OWLDataPropertyAxiom> sourceAxioms = source.getAxioms(prop);
                Set<OWLDataPropertyAxiom> targetAxioms = target.getAxioms(prop);
                Set<OWLAnnotationAssertionAxiom> sourceAnnotations = srcAnnot.getOrDefault(prop.getIRI(), Collections.emptySet());
                Set<OWLAnnotationAssertionAxiom> targetAnnotations = tgtAnnot.getOrDefault(prop.getIRI(), Collections.emptySet());

                if (!sourceAxioms.equals(targetAxioms) || !sourceAnnotations.equals(targetAnnotations)) {
                    MergeConflict conflict = new MergeConflict();
                    conflict.setEntityIRI(prop.getIRI().toString());
                    conflict.setEntityType("DataProperty");
                    conflict.setConflictType(ConflictType.DIFFERENT_DEFINITION);
                    Set<OWLAxiom> allSource = new HashSet<>(sourceAxioms);
                    allSource.addAll(sourceAnnotations);
                    Set<OWLAxiom> allTarget = new HashSet<>(targetAxioms);
                    allTarget.addAll(targetAnnotations);
                    conflict.setSourceDefinition(formatAxioms(allSource));
                    conflict.setTargetDefinition(formatAxioms(allTarget));
                    result.addConflict(conflict);
                }
            }
        }
    }

    private void detectIndividualConflicts(OWLOntology source, OWLOntology target,
                                          Map<IRI, Set<OWLAnnotationAssertionAxiom>> srcAnnot,
                                          Map<IRI, Set<OWLAnnotationAssertionAxiom>> tgtAnnot,
                                          MergeAnalysisResult result) {
        Set<OWLNamedIndividual> sourceIndividuals = source.getIndividualsInSignature();
        Set<OWLNamedIndividual> targetIndividuals = target.getIndividualsInSignature();

        for (OWLNamedIndividual individual : sourceIndividuals) {
            if (targetIndividuals.contains(individual)) {
                Set<OWLIndividualAxiom> sourceAxioms = source.getAxioms(individual);
                Set<OWLIndividualAxiom> targetAxioms = target.getAxioms(individual);
                Set<OWLAnnotationAssertionAxiom> sourceAnnotations = srcAnnot.getOrDefault(individual.getIRI(), Collections.emptySet());
                Set<OWLAnnotationAssertionAxiom> targetAnnotations = tgtAnnot.getOrDefault(individual.getIRI(), Collections.emptySet());

                if (!sourceAxioms.equals(targetAxioms) || !sourceAnnotations.equals(targetAnnotations)) {
                    MergeConflict conflict = new MergeConflict();
                    conflict.setEntityIRI(individual.getIRI().toString());
                    conflict.setEntityType("Individual");
                    conflict.setConflictType(ConflictType.DIFFERENT_ASSERTIONS);
                    Set<OWLAxiom> allSource = new HashSet<>(sourceAxioms);
                    allSource.addAll(sourceAnnotations);
                    Set<OWLAxiom> allTarget = new HashSet<>(targetAxioms);
                    allTarget.addAll(targetAnnotations);
                    conflict.setSourceDefinition(formatAxioms(allSource));
                    conflict.setTargetDefinition(formatAxioms(allTarget));
                    result.addConflict(conflict);
                }
            }
        }
    }

    private void detectAnnotationPropertyConflicts(OWLOntology source, OWLOntology target,
                                                   Map<IRI, Set<OWLAnnotationAssertionAxiom>> srcAnnot,
                                                   Map<IRI, Set<OWLAnnotationAssertionAxiom>> tgtAnnot,
                                                   MergeAnalysisResult result) {
        Set<OWLAnnotationProperty> sourceAnnotProps = source.getAnnotationPropertiesInSignature();
        Set<OWLAnnotationProperty> targetAnnotProps = target.getAnnotationPropertiesInSignature();

        for (OWLAnnotationProperty prop : sourceAnnotProps) {
            if (targetAnnotProps.contains(prop)) {
                Set<OWLAnnotationAxiom> sourceAxioms = source.getAxioms(prop);
                Set<OWLAnnotationAxiom> targetAxioms = target.getAxioms(prop);
                Set<OWLAnnotationAssertionAxiom> sourceAnnotations = srcAnnot.getOrDefault(prop.getIRI(), Collections.emptySet());
                Set<OWLAnnotationAssertionAxiom> targetAnnotations = tgtAnnot.getOrDefault(prop.getIRI(), Collections.emptySet());

                if (!sourceAxioms.equals(targetAxioms) || !sourceAnnotations.equals(targetAnnotations)) {
                    MergeConflict conflict = new MergeConflict();
                    conflict.setEntityIRI(prop.getIRI().toString());
                    conflict.setEntityType("AnnotationProperty");
                    conflict.setConflictType(ConflictType.DIFFERENT_DEFINITION);
                    Set<OWLAxiom> allSource = new HashSet<>(sourceAxioms);
                    allSource.addAll(sourceAnnotations);
                    Set<OWLAxiom> allTarget = new HashSet<>(targetAxioms);
                    allTarget.addAll(targetAnnotations);
                    conflict.setSourceDefinition(formatAxioms(allSource));
                    conflict.setTargetDefinition(formatAxioms(allTarget));
                    result.addConflict(conflict);
                }
            }
        }
    }

    private void detectAxiomConflicts(OWLOntology source, OWLOntology target,
                                     MergeAnalysisResult result) {

        Map<OWLClass, List<OWLDisjointClassesAxiom>> targetDisjointByClass = new HashMap<>();
        for (OWLDisjointClassesAxiom tgtAx : target.getAxioms(AxiomType.DISJOINT_CLASSES)) {
            for (OWLClass cls : tgtAx.getClassesInSignature()) {
                targetDisjointByClass.computeIfAbsent(cls, k -> new ArrayList<>()).add(tgtAx);
            }
        }
        Set<OWLDisjointClassesAxiom> reportedPairs = new HashSet<>();
        for (OWLDisjointClassesAxiom sourceDisjoint : source.getAxioms(AxiomType.DISJOINT_CLASSES)) {
            for (OWLClass cls : sourceDisjoint.getClassesInSignature()) {
                List<OWLDisjointClassesAxiom> candidates = targetDisjointByClass.get(cls);
                if (candidates == null) continue;
                for (OWLDisjointClassesAxiom targetDisjoint : candidates) {
                    if (sourceDisjoint.equals(targetDisjoint) || !reportedPairs.add(targetDisjoint)) continue;
                    MergeConflict conflict = new MergeConflict();
                    conflict.setEntityIRI(cls.getIRI().toString());
                    conflict.setEntityType("DisjointClassesAxiom");
                    conflict.setConflictType(ConflictType.INCOMPATIBLE_AXIOMS);
                    conflict.setSourceDefinition(sourceDisjoint.toString());
                    conflict.setTargetDefinition(targetDisjoint.toString());
                    result.addConflict(conflict);
                }
            }
        }
    }

    private Set<OWLAxiom> getAllAxiomsForIRI(OWLOntology ontology, IRI entityIRI) {
        Set<OWLAxiom> axioms = new HashSet<>();
        for (OWLAxiom axiom : ontology.getAxioms()) {
            if (axiomReferencesIRI(axiom, entityIRI)) {
                axioms.add(axiom);
            }
        }
        return axioms;
    }

    private boolean axiomReferencesIRI(OWLAxiom axiom, IRI entityIRI) {
        for (OWLEntity e : axiom.getSignature()) {
            if (e.getIRI().equals(entityIRI)) {
                return true;
            }
        }
        if (axiom instanceof OWLAnnotationAssertionAxiom) {
            OWLAnnotationSubject subject = ((OWLAnnotationAssertionAxiom) axiom).getSubject();
            if (subject instanceof IRI && entityIRI.equals(subject)) {
                return true;
            }
        }
        return false;
    }

    private Set<OWLAxiom> getDefiningAxioms(OWLOntology ontology, IRI entityIRI) {
        Set<OWLAxiom> defining = new HashSet<>();

        for (OWLDeclarationAxiom ax : ontology.getAxioms(AxiomType.DECLARATION)) {
            if (ax.getEntity().getIRI().equals(entityIRI)) {
                defining.add(ax);
            }
        }

        for (OWLAnnotationAssertionAxiom ax : ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
            if (ax.getSubject().equals(entityIRI)) {
                defining.add(ax);
            }
        }

        if (ontology.containsClassInSignature(entityIRI)) {
            OWLClass cls = ontology.getOWLOntologyManager().getOWLDataFactory().getOWLClass(entityIRI);
            for (OWLSubClassOfAxiom ax : ontology.getSubClassAxiomsForSubClass(cls)) {
                defining.add(ax);
            }
            defining.addAll(ontology.getEquivalentClassesAxioms(cls));
            defining.addAll(ontology.getDisjointClassesAxioms(cls));
            defining.addAll(ontology.getDisjointUnionAxioms(cls));
        }

        if (ontology.containsObjectPropertyInSignature(entityIRI)) {
            OWLObjectProperty prop = ontology.getOWLOntologyManager().getOWLDataFactory().getOWLObjectProperty(entityIRI);
            for (OWLSubObjectPropertyOfAxiom ax : ontology.getAxioms(AxiomType.SUB_OBJECT_PROPERTY)) {
                if (!ax.getSubProperty().isAnonymous() && ax.getSubProperty().asOWLObjectProperty().getIRI().equals(entityIRI)) {
                    defining.add(ax);
                }
            }
            defining.addAll(ontology.getObjectPropertyDomainAxioms(prop));
            defining.addAll(ontology.getObjectPropertyRangeAxioms(prop));
            defining.addAll(ontology.getFunctionalObjectPropertyAxioms(prop));
            defining.addAll(ontology.getInverseFunctionalObjectPropertyAxioms(prop));
            defining.addAll(ontology.getTransitiveObjectPropertyAxioms(prop));
            defining.addAll(ontology.getSymmetricObjectPropertyAxioms(prop));
            defining.addAll(ontology.getAsymmetricObjectPropertyAxioms(prop));
            defining.addAll(ontology.getReflexiveObjectPropertyAxioms(prop));
            defining.addAll(ontology.getIrreflexiveObjectPropertyAxioms(prop));
            defining.addAll(ontology.getEquivalentObjectPropertiesAxioms(prop));
            defining.addAll(ontology.getDisjointObjectPropertiesAxioms(prop));
            defining.addAll(ontology.getInverseObjectPropertyAxioms(prop));
        }

        if (ontology.containsDataPropertyInSignature(entityIRI)) {
            OWLDataProperty prop = ontology.getOWLOntologyManager().getOWLDataFactory().getOWLDataProperty(entityIRI);
            for (OWLSubDataPropertyOfAxiom ax : ontology.getAxioms(AxiomType.SUB_DATA_PROPERTY)) {
                if (ax.getSubProperty().asOWLDataProperty().getIRI().equals(entityIRI)) {
                    defining.add(ax);
                }
            }
            defining.addAll(ontology.getDataPropertyDomainAxioms(prop));
            defining.addAll(ontology.getDataPropertyRangeAxioms(prop));
            defining.addAll(ontology.getFunctionalDataPropertyAxioms(prop));
            defining.addAll(ontology.getEquivalentDataPropertiesAxioms(prop));
            defining.addAll(ontology.getDisjointDataPropertiesAxioms(prop));
        }

        if (ontology.containsIndividualInSignature(entityIRI)) {
            OWLNamedIndividual ind = ontology.getOWLOntologyManager().getOWLDataFactory().getOWLNamedIndividual(entityIRI);
            defining.addAll(ontology.getClassAssertionAxioms(ind));
            defining.addAll(ontology.getObjectPropertyAssertionAxioms(ind));
            defining.addAll(ontology.getDataPropertyAssertionAxioms(ind));
            defining.addAll(ontology.getNegativeObjectPropertyAssertionAxioms(ind));
            defining.addAll(ontology.getNegativeDataPropertyAssertionAxioms(ind));
            defining.addAll(ontology.getSameIndividualAxioms(ind));
            defining.addAll(ontology.getDifferentIndividualAxioms(ind));
        }

        if (ontology.containsAnnotationPropertyInSignature(entityIRI)) {
            OWLAnnotationProperty prop = ontology.getOWLOntologyManager().getOWLDataFactory().getOWLAnnotationProperty(entityIRI);
            defining.addAll(ontology.getAxioms(prop));

            for (OWLSubAnnotationPropertyOfAxiom ax : ontology.getAxioms(AxiomType.SUB_ANNOTATION_PROPERTY_OF)) {
                if (ax.getSubProperty().getIRI().equals(entityIRI) || ax.getSuperProperty().getIRI().equals(entityIRI)) {
                    defining.add(ax);
                }
            }

            for (OWLAnnotationAssertionAxiom ax : ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
                if (ax.getProperty().equals(prop)) {
                    defining.add(ax);
                }
            }
        }

        return defining;
    }

    private void removeDefiningAxioms(OWLOntology ontology, IRI entityIRI, OWLOntologyManager manager) {
        Set<OWLAxiom> toRemove = getDefiningAxioms(ontology, entityIRI);
        if (!toRemove.isEmpty()) {
            List<OWLOntologyChange> changes = new ArrayList<>();
            for (OWLAxiom axiom : toRemove) {
                changes.add(new RemoveAxiom(ontology, axiom));
            }
            manager.applyChanges(changes);
        }
    }

    private void addDefiningAxioms(OWLOntology source, OWLOntology target, IRI entityIRI,
                                   OWLOntologyManager manager) {
        Set<OWLAxiom> toAdd = getDefiningAxioms(source, entityIRI);
        for (OWLAxiom axiom : toAdd) {
            manager.addAxiom(target, axiom);
        }
    }

    private void addEntityAxioms(OWLOntology source, OWLOntology target, IRI entityIRI,
                                OWLOntologyManager manager) {
        Set<OWLAxiom> toAdd = getAllAxiomsForIRI(source, entityIRI);
        for (OWLAxiom axiom : toAdd) {
            manager.addAxiom(target, axiom);
        }
    }

    private void addEntityWithRename(OWLOntology source, OWLOntology target, IRI entityIRI,
                                    String suffix, OWLOntologyManager manager,
                                    OWLDataFactory factory) {

        OWLEntity entityToRename = null;

        if (source.containsClassInSignature(entityIRI)) {
            entityToRename = factory.getOWLClass(entityIRI);
        } else if (source.containsObjectPropertyInSignature(entityIRI)) {
            entityToRename = factory.getOWLObjectProperty(entityIRI);
        } else if (source.containsDataPropertyInSignature(entityIRI)) {
            entityToRename = factory.getOWLDataProperty(entityIRI);
        } else if (source.containsIndividualInSignature(entityIRI)) {
            entityToRename = factory.getOWLNamedIndividual(entityIRI);
        } else if (source.containsAnnotationPropertyInSignature(entityIRI)) {
            entityToRename = factory.getOWLAnnotationProperty(entityIRI);
        } else if (source.containsDatatypeInSignature(entityIRI)) {
            entityToRename = factory.getOWLDatatype(entityIRI);
        }

        if (entityToRename != null) {

            IRI newIRI = IRI.create(entityIRI.toString() + suffix);
            Map<OWLEntity, IRI> renameMap = new HashMap<>();
            renameMap.put(entityToRename, newIRI);

            OWLEntityRenamer renamer = new OWLEntityRenamer(manager, Collections.singleton(source));
            List<OWLOntologyChange> changes = renamer.changeIRI(renameMap);
            manager.applyChanges(changes);

            addEntityAxioms(source, target, newIRI, manager);
        }
    }

    private boolean isConflictingAxiom(OWLAxiom axiom, Set<String> conflictEntityIRIs) {
        for (String iriStr : conflictEntityIRIs) {
            if (axiomReferencesIRI(axiom, IRI.create(iriStr))) {
                return true;
            }
        }
        return false;
    }

    private String formatAxioms(Set<? extends OWLAxiom> axioms) {
        return axioms.stream()
                    .map(OWLAxiom::toString)
                    .collect(Collectors.joining("\n"));
    }

    private Map<String, List<String>> buildClassHierarchy(OWLOntology source, OWLOntology target) {
        Map<String, Set<String>> hierarchy = new HashMap<>();

        for (OWLOntology ont : List.of(source, target)) {
            for (OWLSubClassOfAxiom ax : ont.getAxioms(AxiomType.SUBCLASS_OF)) {
                if (ax.getSubClass().isAnonymous() || ax.getSuperClass().isAnonymous()) continue;
                String parentIRI = ax.getSuperClass().asOWLClass().getIRI().toString();
                String childIRI = ax.getSubClass().asOWLClass().getIRI().toString();
                hierarchy.computeIfAbsent(parentIRI, k -> new LinkedHashSet<>()).add(childIRI);
            }
        }

        Map<String, List<String>> result = new HashMap<>();
        hierarchy.forEach((parent, children) -> result.put(parent, new ArrayList<>(children)));
        return result;
    }

    private Map<String, List<String>> buildPropertyHierarchy(OWLOntology source, OWLOntology target) {
        Map<String, Set<String>> hierarchy = new HashMap<>();

        for (OWLOntology ont : List.of(source, target)) {
            for (OWLSubObjectPropertyOfAxiom ax : ont.getAxioms(AxiomType.SUB_OBJECT_PROPERTY)) {
                if (ax.getSubProperty().isAnonymous() || ax.getSuperProperty().isAnonymous()) continue;
                String parentIRI = ax.getSuperProperty().asOWLObjectProperty().getIRI().toString();
                String childIRI = ax.getSubProperty().asOWLObjectProperty().getIRI().toString();
                hierarchy.computeIfAbsent(parentIRI, k -> new LinkedHashSet<>()).add(childIRI);
            }
            for (OWLSubDataPropertyOfAxiom ax : ont.getAxioms(AxiomType.SUB_DATA_PROPERTY)) {
                String parentIRI = ax.getSuperProperty().asOWLDataProperty().getIRI().toString();
                String childIRI = ax.getSubProperty().asOWLDataProperty().getIRI().toString();
                hierarchy.computeIfAbsent(parentIRI, k -> new LinkedHashSet<>()).add(childIRI);
            }
            for (OWLSubAnnotationPropertyOfAxiom ax : ont.getAxioms(AxiomType.SUB_ANNOTATION_PROPERTY_OF)) {
                String parentIRI = ax.getSuperProperty().getIRI().toString();
                String childIRI = ax.getSubProperty().getIRI().toString();
                hierarchy.computeIfAbsent(parentIRI, k -> new LinkedHashSet<>()).add(childIRI);
            }
        }

        Map<String, List<String>> result = new HashMap<>();
        hierarchy.forEach((parent, children) -> result.put(parent, new ArrayList<>(children)));
        return result;
    }

    private OWLOntology cloneAsAnonymousOntology(OWLOntologyManager manager, OWLOntology original)
            throws OWLOntologyCreationException {
        IRI scratchIri = IRI.create("http://ontocode.org/merge-scratch/" + java.util.UUID.randomUUID());
        OWLOntology clone = manager.createOntology(scratchIri);
        manager.addAxioms(clone, original.getAxioms());
        for (OWLAnnotation annotation : original.getAnnotations()) {
            manager.applyChange(new AddOntologyAnnotation(clone, annotation));
        }
        for (OWLImportsDeclaration importDecl : original.getImportsDeclarations()) {
            manager.applyChange(new AddImport(clone, importDecl));
        }
        return clone;
    }

    private Map<IRI, Set<OWLAnnotationAssertionAxiom>> buildAnnotationIndex(OWLOntology ontology) {
        Map<IRI, Set<OWLAnnotationAssertionAxiom>> index = new HashMap<>();
        for (OWLAnnotationAssertionAxiom ax : ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
            OWLAnnotationSubject subject = ax.getSubject();
            if (subject instanceof IRI iri) {
                index.computeIfAbsent(iri, k -> new HashSet<>()).add(ax);
            }
        }
        return index;
    }

    private Map<IRI, String> buildLabelIndex(OWLOntology ontology) {
        Map<IRI, String> labels = new HashMap<>();
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
            OWLAnnotationSubject subject = axiom.getSubject();
            if (!(subject instanceof IRI entityIri)) continue;
            if (labels.containsKey(entityIri)) continue;
            if (!(axiom.getValue() instanceof OWLLiteral literal)) continue;

            IRI propIri = axiom.getProperty().getIRI();
            String iriString = propIri.toString();
            if (propIri.equals(OWLRDFVocabulary.RDFS_LABEL.getIRI())
                || iriString.endsWith("#label")
                || iriString.endsWith("/label")
                || iriString.endsWith("#prefLabel")
                || iriString.endsWith("/prefLabel")
                || iriString.endsWith("#title")
                || iriString.endsWith("/title")) {
                String label = literal.getLiteral();
                if (label != null && !label.isBlank()) {
                    labels.put(entityIri, label);
                }
            }
        }
        return labels;
    }

    private String lookupLabel(Map<IRI, String> labelIndex, IRI entityIri) {
        String cached = labelIndex.get(entityIri);
        if (cached != null) return cached;
        String iri = entityIri.toString();
        String local = iri.contains("#") ? iri.substring(iri.lastIndexOf('#') + 1) : iri.substring(iri.lastIndexOf('/') + 1);
        return local.isBlank() ? iri : local;
    }

    public OWLOntology loadOntologyFromRdf(String rdfXml) throws OWLOntologyCreationException {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);
        OWLOntology loaded = manager.loadOntologyFromOntologyDocument(
                new StringDocumentSource(rdfXml), config);
        return cloneAsAnonymousOntology(manager, loaded);
    }

    public String saveOntologyToRdfXml(OWLOntology ontology) throws OWLOntologyStorageException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ontology.getOWLOntologyManager().saveOntology(ontology, new RDFXMLDocumentFormat(), out);
        return out.toString(StandardCharsets.UTF_8);
    }

    public void removeEntityFromOntology(OWLOntology ontology, IRI entityIRI) {
        removeDefiningAxioms(ontology, entityIRI, ontology.getOWLOntologyManager());
    }

    public OWLOntology mergeDraftPublishThreeWay(OWLOntology baseline,
                                                 OWLOntology ours,
                                                 OWLOntology theirs,
                                                 Set<String> conflictEntityIris,
                                                 Map<String, ConflictResolution> resolutions)
            throws OWLOntologyCreationException {
        OWLOntologyManager manager = theirs.getOWLOntologyManager();
        Set<String> touchedIris = collectTouchedIris(baseline, ours);
        Map<String, ConflictResolution> safeResolutions =
                resolutions != null ? resolutions : Collections.emptyMap();

        log.info("[DRAFT-MERGE] Three-way merge: {} touched IRIs, {} conflicts",
                touchedIris.size(), conflictEntityIris != null ? conflictEntityIris.size() : 0);

        for (String iriStr : touchedIris) {
            IRI iri = IRI.create(iriStr);
            Set<OWLAxiom> baseDefs = getDefiningAxioms(baseline, iri);
            Set<OWLAxiom> ourDefs = getDefiningAxioms(ours, iri);
            if (ourDefs.equals(baseDefs)) {
                continue;
            }

            if (conflictEntityIris != null && conflictEntityIris.contains(iriStr)) {
                ConflictResolution resolution = safeResolutions.get(iriStr);
                if (resolution == null) {
                    resolution = new ConflictResolution();
                    resolution.setAction(ResolutionAction.MERGE);
                }
                applyDraftConflictResolution(ours, theirs, iri, resolution, manager);
            } else {
                removeDefiningAxioms(theirs, iri, manager);
                addDefiningAxioms(ours, theirs, iri, manager);
            }
        }
        return theirs;
    }

    public Set<String> collectTouchedIris(OWLOntology baseline, OWLOntology ours) {
        Set<String> touched = new LinkedHashSet<>();
        for (OWLEntity entity : ours.getSignature()) {
            if (entity.isBuiltIn()) {
                continue;
            }
            IRI iri = entity.getIRI();
            if (!getDefiningAxioms(baseline, iri).equals(getDefiningAxioms(ours, iri))) {
                touched.add(iri.toString());
            }
        }
        return touched;
    }

    private void applyDraftConflictResolution(OWLOntology ours,
                                              OWLOntology target,
                                              IRI entityIRI,
                                              ConflictResolution resolution,
                                              OWLOntologyManager manager) {
        OWLDataFactory factory = manager.getOWLDataFactory();
        switch (resolution.getAction()) {
            case KEEP_SOURCE -> {
                removeDefiningAxioms(target, entityIRI, manager);
                addDefiningAxioms(ours, target, entityIRI, manager);
            }
            case KEEP_TARGET -> {

            }
            case MERGE -> addDefiningAxioms(ours, target, entityIRI, manager);
            case RENAME_SOURCE -> {
                String suffix = resolution.getRenameSuffix() != null
                        ? resolution.getRenameSuffix() : "_draft";
                addEntityWithRename(ours, target, entityIRI, suffix, manager, factory);
            }
            case SKIP -> removeDefiningAxioms(target, entityIRI, manager);
        }
    }
}
