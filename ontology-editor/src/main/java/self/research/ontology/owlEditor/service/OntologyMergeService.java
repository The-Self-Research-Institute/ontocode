package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.OWLEntityRenamer;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.merge.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for merging ontologies similar to Protege PROMPT.
 * Supports:
 * - Simple merge (union of ontologies)
 * - Conflict detection (duplicate classes, properties, different definitions)
 * - Conflict resolution strategies
 * - Semantic alignment and mapping
 */
@Service
public class OntologyMergeService {

    private static final Logger log = LoggerFactory.getLogger(OntologyMergeService.class);

    private final GraphDBDatasetService datasetService;
    private final ProjectImportService importService;
    private final StorageManager storageManager;
    private final ProjectMetadataService metadataService;

    public OntologyMergeService(GraphDBDatasetService datasetService,
                               ProjectImportService importService,
                               StorageManager storageManager,
                               ProjectMetadataService metadataService) {
        this.datasetService = datasetService;
        this.importService = importService;
        this.storageManager = storageManager;
        this.metadataService = metadataService;
    }

    /**
     * Analyze two ontologies for potential conflicts before merging
     */
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
        
        // Configure to ignore missing imports (don't try to download from web)
        OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
            .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);
        
        // Load source ontology from file without resolving imports
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

        // Pre-build annotation assertion indexes (IRI → Set<axiom>) in ONE pass
        // so conflict detection never re-scans all annotations per entity.
        Map<IRI, Set<OWLAnnotationAssertionAxiom>> sourceAnnotIndex = buildAnnotationIndex(sourceOntology);
        Map<IRI, Set<OWLAnnotationAssertionAxiom>> targetAnnotIndex = buildAnnotationIndex(targetOntology);

        // Pre-build label index from source (IRI → label) in ONE pass
        Map<IRI, String> sourceLabelIndex = buildLabelIndex(sourceOntology);
        
        // Detect class conflicts
        detectClassConflicts(sourceOntology, targetOntology, sourceAnnotIndex, targetAnnotIndex, result);
        
        // Detect property conflicts (object, data, annotation)
        detectPropertyConflicts(sourceOntology, targetOntology, sourceAnnotIndex, targetAnnotIndex, result);
        detectAnnotationPropertyConflicts(sourceOntology, targetOntology, sourceAnnotIndex, targetAnnotIndex, result);
        
        // Detect individual conflicts
        detectIndividualConflicts(sourceOntology, targetOntology, sourceAnnotIndex, targetAnnotIndex, result);
        
        // Detect axiom conflicts
        detectAxiomConflicts(sourceOntology, targetOntology, result);
        
        // Calculate statistics
        result.setSourceClassCount((int) sourceOntology.getClassesInSignature().stream().filter(c -> !c.isBuiltIn()).count());
        result.setSourcePropertyCount((int) sourceOntology.getObjectPropertiesInSignature().size() + 
                                      (int) sourceOntology.getDataPropertiesInSignature().size());
        result.setTargetClassCount((int) targetOntology.getClassesInSignature().stream().filter(c -> !c.isBuiltIn()).count());
        result.setTargetPropertyCount((int) targetOntology.getObjectPropertiesInSignature().size() + 
                                      (int) targetOntology.getDataPropertiesInSignature().size());
        result.setSourceIndividualCount((int) sourceOntology.getIndividualsInSignature().size());
        result.setTargetIndividualCount((int) targetOntology.getIndividualsInSignature().size());

        // Compute source-vs-target differences so UI can show what will be added from source.
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

        // Count axiom differences without copying the full axiom sets.
        // Build one HashSet and probe the other to avoid creating two huge sets.
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

        // Build class hierarchy (parent → direct children) from both ontologies
        // so the frontend can cascade conflict resolutions to subclasses
        result.setClassHierarchy(buildClassHierarchy(sourceOntology, targetOntology));
        result.setPropertyHierarchy(buildPropertyHierarchy(sourceOntology, targetOntology));

        log.info("[MERGE] Analysis complete: {} conflicts detected", result.getTotalConflicts());
        
        // Clean up: remove ontologies from manager to release file handles (important for Windows)
        manager.removeOntology(sourceOntology);
        manager.removeOntology(targetOntology);
        
        return result;
    }

    /**
     * Perform the actual merge with specified options
     */
    public MergeResult mergeOntologies(String sourceProjectId, Path sourceFile,
                                      String targetProjectId,
                                      String targetFileName,
                                      String outputFileName,
                                      MergeOptions options) throws Exception {
        log.info("[MERGE] Starting merge operation");
        log.info("[MERGE] Source: {}, Target: {}", sourceFile, targetProjectId);
        log.info("[MERGE] Strategy: {}", options.getStrategy());

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        
        // Configure to ignore missing imports (don't try to download from web)
        OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
            .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);
        
        // Load both ontologies without resolving imports
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

            // Save merged ontology either back to selected target file or to a new file/project.
            Path outputPath;
            String importProjectId = targetProjectId; // Which project to import into GraphDB
            boolean isNewFile = outputFileName != null && !outputFileName.isBlank();

            if (isNewFile) {
                // "Save as new file" mode: save to a temporary project directory so the
                // frontend can download the merged content and upload it to the auth
                // service as a new project file. We do NOT import into GraphDB here –
                // the normal file-open flow will handle the import when the user opens
                // the new file from the project file list.
                String tempProjectId = "merge-temp-" + java.util.UUID.randomUUID();
                log.info("[MERGE] Saving merged output to temp project '{}' for file '{}'", tempProjectId, outputFileName);

                try {
                    storageManager.prepareProjectDir(tempProjectId);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to create temp directory for merge output: " + tempProjectId, e);
                }

                // Save as ontology.current.owl so the standard download endpoint can find it
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
                // Only refresh metadata and reimport for "merge into existing" modes.
                // For "save as new file", the auth upload + file-open flow handles this.
                refreshMergedMetadata(importProjectId, targetOntology);
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
            // Clean up: remove ontologies from manager to release file handles (important for Windows)
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

    /**
     * Simple union - combine all axioms from both ontologies.
     * Directly transfers every axiom type (declarations, logical axioms,
     * annotation assertions, etc.) from source to target.
     */
    private void performSimpleUnion(OWLOntology source, OWLOntology target, 
                                   OWLOntologyManager manager, MergeResult result) throws OWLOntologyCreationException {
        log.info("[MERGE] Performing simple union merge (direct axiom transfer)");
        
        int addedCount = 0;
        
        // Add ALL axioms from source to target – this covers declarations,
        // logical axioms, annotation assertions, SWRL rules, etc.
        for (OWLAxiom axiom : source.getAxioms()) {
            if (!target.containsAxiom(axiom)) {
                manager.addAxiom(target, axiom);
                addedCount++;
            }
        }
        
        // Copy ontology-level annotations from source
        for (OWLAnnotation annotation : source.getAnnotations()) {
            if (!target.getAnnotations().contains(annotation)) {
                manager.applyChange(new AddOntologyAnnotation(target, annotation));
            }
        }
        
        // Copy imports declarations from source
        for (OWLImportsDeclaration importDecl : source.getImportsDeclarations()) {
            if (!target.getImportsDeclarations().contains(importDecl)) {
                manager.applyChange(new AddImport(target, importDecl));
            }
        }
        
        result.setAxiomsAdded(addedCount);
        log.info("[MERGE] Added {} new axioms via simple union", addedCount);
    }

    /**
     * Replace duplicates - source definitions fully overwrite target definitions.
     * For shared entities (same IRI in both ontologies), the target's definition
     * is removed and replaced by the source's definition.
     *
     * Target-only children of shared parents are FULLY REMOVED (not just orphaned)
     * if they don't exist in the source. For example, if source has Animal→{Dog,Cat}
     * and target has Animal→{Dog,Horse}, then Horse is completely deleted from the
     * merged result. This recursively removes descendants too.
     */
    private void performReplaceDuplicates(OWLOntology source, OWLOntology target,
                                         OWLOntologyManager manager, MergeOptions options,
                                         MergeResult result) {
        log.info("[MERGE] Performing replace duplicates merge");
        
        Set<IRI> sourceIRIs = new HashSet<>();
        for (OWLEntity e : source.getSignature()) {
            sourceIRIs.add(e.getIRI());
        }
        
        // Step 1: Identify shared entities (present in both source and target)
        Set<IRI> sharedIRIs = new HashSet<>();
        for (OWLEntity entity : source.getSignature()) {
            if (target.containsEntityInSignature(entity)) {
                sharedIRIs.add(entity.getIRI());
            }
        }
        log.info("[MERGE] Found {} shared entities — source will replace target definitions", sharedIRIs.size());
        
        // Step 2: Find target-only entities that are children of shared parents.
        // These are entities that exist in target but NOT in source, and have a
        // hierarchy axiom linking them to a shared parent.
        Set<IRI> entitiesToFullyRemove = new HashSet<>();
        Set<OWLAxiom> sourceAxiomSet = new HashSet<>(source.getAxioms());
        
        // SubClassOf(X, SharedClass) where X is not in source
        for (OWLSubClassOfAxiom ax : target.getAxioms(AxiomType.SUBCLASS_OF)) {
            if (ax.getSuperClass().isAnonymous() || ax.getSubClass().isAnonymous()) continue;
            IRI superIRI = ax.getSuperClass().asOWLClass().getIRI();
            IRI subIRI = ax.getSubClass().asOWLClass().getIRI();
            if (sharedIRIs.contains(superIRI) && !sourceIRIs.contains(subIRI)) {
                entitiesToFullyRemove.add(subIRI);
            }
        }
        
        // SubObjectPropertyOf(X, SharedProp) where X is not in source
        for (OWLSubObjectPropertyOfAxiom ax : target.getAxioms(AxiomType.SUB_OBJECT_PROPERTY)) {
            if (ax.getSuperProperty().isAnonymous() || ax.getSubProperty().isAnonymous()) continue;
            IRI superIRI = ax.getSuperProperty().asOWLObjectProperty().getIRI();
            IRI subIRI = ax.getSubProperty().asOWLObjectProperty().getIRI();
            if (sharedIRIs.contains(superIRI) && !sourceIRIs.contains(subIRI)) {
                entitiesToFullyRemove.add(subIRI);
            }
        }
        
        // SubDataPropertyOf(X, SharedProp) where X is not in source
        for (OWLSubDataPropertyOfAxiom ax : target.getAxioms(AxiomType.SUB_DATA_PROPERTY)) {
            IRI superIRI = ax.getSuperProperty().asOWLDataProperty().getIRI();
            IRI subIRI = ax.getSubProperty().asOWLDataProperty().getIRI();
            if (sharedIRIs.contains(superIRI) && !sourceIRIs.contains(subIRI)) {
                entitiesToFullyRemove.add(subIRI);
            }
        }
        
        // ClassAssertion(SharedClass, individual) where individual is not in source
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
        
        // Recursively find descendants of entities to remove
        // (e.g. if Horse is removed and Horse has subclass Pony, Pony is also removed)
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
        
        // Step 3: Collect ALL axioms to remove from target.
        Set<OWLAxiom> toRemove = new HashSet<>();
        
        // 3a: Defining axioms of shared entities
        for (IRI iri : sharedIRIs) {
            toRemove.addAll(getDefiningAxioms(target, iri));
        }
        
        // 3b: ALL axioms referencing entities to fully remove
        for (IRI iri : entitiesToFullyRemove) {
            toRemove.addAll(getAllAxiomsForIRI(target, iri));
        }
        
        // 3c: Remaining hierarchy axioms where parent is shared but axiom not in source
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
        
        // Step 4: Apply all removals atomically
        if (!toRemove.isEmpty()) {
            List<OWLOntologyChange> removals = new ArrayList<>();
            for (OWLAxiom axiom : toRemove) {
                removals.add(new RemoveAxiom(target, axiom));
            }
            manager.applyChanges(removals);
        }
        
        // Step 5: Add ALL source axioms to target.
        int addedCount = 0;
        for (OWLAxiom axiom : source.getAxioms()) {
            if (!target.containsAxiom(axiom)) {
                manager.addAxiom(target, axiom);
                addedCount++;
            }
        }
        
        // Step 6: Copy ontology-level annotations from source
        for (OWLAnnotation annotation : source.getAnnotations()) {
            if (!target.getAnnotations().contains(annotation)) {
                manager.applyChange(new AddOntologyAnnotation(target, annotation));
            }
        }
        
        // Step 7: Copy imports declarations from source
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

    /**
     * Keep both - rename source entities to avoid conflicts using OWLEntityRenamer
     * This matches Protege's renaming behavior
     */
    private void performKeepBoth(OWLOntology source, OWLOntology target,
                                OWLOntologyManager manager, MergeOptions options,
                                MergeResult result) {
        log.info("[MERGE] Performing keep both merge with renaming using OWLEntityRenamer");
        
        String renameSuffix = options.getRenameSuffix() != null ? options.getRenameSuffix() : "_imported";
        
        Map<OWLEntity, IRI> renameMap = new HashMap<>();
        Set<OWLEntity> sourceEntities = source.getSignature();
        
        // Create rename map for conflicting entities
        for (OWLEntity entity : sourceEntities) {
            if (target.containsEntityInSignature(entity)) {
                IRI newIRI = IRI.create(entity.getIRI().toString() + renameSuffix);
                renameMap.put(entity, newIRI);
                log.debug("[MERGE] Will rename {} to {}", entity.getIRI(), newIRI);
            }
        }
        
        // Use OWLEntityRenamer to properly rename entities in all axioms
        OWLEntityRenamer renamer = new OWLEntityRenamer(manager, Collections.singleton(source));
        List<OWLOntologyChange> changes = renamer.changeIRI(renameMap);
        
        // Apply the renaming changes to the source ontology
        manager.applyChanges(changes);
        log.info("[MERGE] Applied {} renaming changes", changes.size());
        
        // Now merge the renamed source into target
        int addedCount = 0;
        for (OWLAxiom axiom : source.getAxioms()) {
            manager.addAxiom(target, axiom);
            addedCount++;
        }
        
        // Add ontology annotations from source
        for (OWLAnnotation annotation : source.getAnnotations()) {
            if (!target.getAnnotations().contains(annotation)) {
                manager.applyChange(new AddOntologyAnnotation(target, annotation));
            }
        }
        
        // Copy imports declarations from source
        for (OWLImportsDeclaration importDecl : source.getImportsDeclarations()) {
            if (!target.getImportsDeclarations().contains(importDecl)) {
                manager.applyChange(new AddImport(target, importDecl));
            }
        }
        
        result.setEntitiesRenamed(renameMap.size());
        result.setAxiomsAdded(addedCount);
        log.info("[MERGE] Renamed {} entities, added {} axioms", renameMap.size(), addedCount);
    }

    /**
     * Manual resolution - apply user-specified conflict resolutions
     * Properly handles annotations and all axiom types
     */
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
        
        // The frontend sends composite keys "entityIRI::entityType" so that
        // two conflicts for the same entity (e.g. Class definition conflict
        // + DisjointClassesAxiom conflict) can carry independent resolutions.
        // Normalise to plain entityIRI, deduplicating so each entity is only
        // processed once (last resolution wins if the same entity appears
        // with different actions).
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
                    // Remove target's defining axioms, add source's defining axioms
                    removeDefiningAxioms(target, iri, manager);
                    addDefiningAxioms(source, target, iri, manager);
                    resolvedCount++;
                    break;
                    
                case KEEP_TARGET:
                    // Do nothing - target is already there
                    resolvedCount++;
                    break;
                    
                case RENAME_SOURCE:
                    // Rename source entity and add using OWLEntityRenamer
                    String suffix = resolution.getRenameSuffix() != null ? 
                                  resolution.getRenameSuffix() : "_imported";
                    addEntityWithRename(source, target, iri, suffix, manager, factory);
                    resolvedCount++;
                    break;
                    
                case MERGE:
                    // Merge both versions (keep all axioms from both — add source's defining axioms)
                    addDefiningAxioms(source, target, iri, manager);
                    resolvedCount++;
                    break;
                    
                case SKIP:
                    // Do nothing — skip this conflict entirely
                    resolvedCount++;
                    break;
            }
        }
        
        // Add non-conflicting axioms from source
        int addedCount = 0;
        for (OWLAxiom axiom : source.getAxioms()) {
            if (!isConflictingAxiom(axiom, entityIRIs)) {
                if (!target.containsAxiom(axiom)) {
                    manager.addAxiom(target, axiom);
                    addedCount++;
                }
            }
        }
        
        // Add ontology-level annotations from source
        for (OWLAnnotation annotation : source.getAnnotations()) {
            if (!target.getAnnotations().contains(annotation)) {
                manager.applyChange(new AddOntologyAnnotation(target, annotation));
            }
        }
        
        // Copy imports declarations from source
        for (OWLImportsDeclaration importDecl : source.getImportsDeclarations()) {
            if (!target.getImportsDeclarations().contains(importDecl)) {
                manager.applyChange(new AddImport(target, importDecl));
            }
        }
        
        result.setConflictsResolved(resolvedCount);
        result.setAxiomsAdded(addedCount);
        log.info("[MERGE] Resolved {} conflicts, added {} new axioms", resolvedCount, addedCount);
    }

    // ========== Conflict Detection Methods ==========

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
        // Object properties
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
        
        // Data properties
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
        // Detect disjoint axioms that might conflict.
        // Index target disjoint axioms by class to avoid O(n²) nested loop.
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

    // ========== Helper Methods ==========

    private Set<OWLAxiom> getAllAxiomsForIRI(OWLOntology ontology, IRI entityIRI) {
        Set<OWLAxiom> axioms = new HashSet<>();
        for (OWLAxiom axiom : ontology.getAxioms()) {
            if (axiomReferencesIRI(axiom, entityIRI)) {
                axioms.add(axiom);
            }
        }
        return axioms;
    }

    /**
     * Check whether an axiom references the given IRI, either via its
     * logical signature (OWLEntity) or as an annotation assertion subject.
     */
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

    /**
     * Get the DEFINING axioms for an entity — axioms where the entity is the
     * primary subject. This does NOT include axioms where the entity is merely
     * referenced (e.g. SubClassOf(Dog, Animal) is a defining axiom of Dog,
     * not of Animal).
     *
     * This is the Protégé-style "definition" of an entity:
     * - Declaration
     * - Annotation assertions about the entity
     * - SubClassOf / EquivalentClasses / DisjointClasses where entity is the subject class
     * - SubPropertyOf / Domain / Range / Characteristics where entity is the subject property
     * - ClassAssertion / PropertyAssertion where entity is the subject individual
     */
    private Set<OWLAxiom> getDefiningAxioms(OWLOntology ontology, IRI entityIRI) {
        Set<OWLAxiom> defining = new HashSet<>();

        // Declaration axioms
        for (OWLDeclarationAxiom ax : ontology.getAxioms(AxiomType.DECLARATION)) {
            if (ax.getEntity().getIRI().equals(entityIRI)) {
                defining.add(ax);
            }
        }

        // Annotation assertions where this entity is the subject
        for (OWLAnnotationAssertionAxiom ax : ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
            if (ax.getSubject().equals(entityIRI)) {
                defining.add(ax);
            }
        }

        // Class axioms — only where entity is the subject (subclass, not superclass)
        if (ontology.containsClassInSignature(entityIRI)) {
            OWLClass cls = ontology.getOWLOntologyManager().getOWLDataFactory().getOWLClass(entityIRI);
            for (OWLSubClassOfAxiom ax : ontology.getSubClassAxiomsForSubClass(cls)) {
                defining.add(ax);
            }
            defining.addAll(ontology.getEquivalentClassesAxioms(cls));
            defining.addAll(ontology.getDisjointClassesAxioms(cls));
            defining.addAll(ontology.getDisjointUnionAxioms(cls));
        }

        // Object property axioms — where entity is the subject property
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

        // Data property axioms
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

        // Individual axioms — where entity is the subject individual
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

        // Annotation property axioms
        if (ontology.containsAnnotationPropertyInSignature(entityIRI)) {
            OWLAnnotationProperty prop = ontology.getOWLOntologyManager().getOWLDataFactory().getOWLAnnotationProperty(entityIRI);
            defining.addAll(ontology.getAxioms(prop));
        }

        return defining;
    }

    /**
     * Remove only the DEFINING axioms of an entity from the ontology.
     * Does not remove axioms where the entity is merely referenced by other entities.
     * Uses batch applyChanges for atomic removal.
     */
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

    /**
     * Add the DEFINING axioms of an entity from source to target.
     */
    private void addDefiningAxioms(OWLOntology source, OWLOntology target, IRI entityIRI,
                                   OWLOntologyManager manager) {
        Set<OWLAxiom> toAdd = getDefiningAxioms(source, entityIRI);
        for (OWLAxiom axiom : toAdd) {
            manager.addAxiom(target, axiom);
        }
    }

    /**
     * Add ALL axioms about an entity from source to target, including declarations,
     * SubClassOf (where the entity is sub or super), property domain/range,
     * annotation assertions, etc.
     */
    private void addEntityAxioms(OWLOntology source, OWLOntology target, IRI entityIRI,
                                OWLOntologyManager manager) {
        Set<OWLAxiom> toAdd = getAllAxiomsForIRI(source, entityIRI);
        for (OWLAxiom axiom : toAdd) {
            manager.addAxiom(target, axiom);
        }
    }

    /**
     * Add entity with renaming using OWLEntityRenamer (Protege-style).
     * Finds the entity in any role (class, property, individual, annotation property, datatype),
     * renames it, and then adds ALL referencing axioms to the target.
     */
    private void addEntityWithRename(OWLOntology source, OWLOntology target, IRI entityIRI,
                                    String suffix, OWLOntologyManager manager, 
                                    OWLDataFactory factory) {
        // Find the entity in source — try all entity types
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
            // Create rename map
            IRI newIRI = IRI.create(entityIRI.toString() + suffix);
            Map<OWLEntity, IRI> renameMap = new HashMap<>();
            renameMap.put(entityToRename, newIRI);
            
            // Use OWLEntityRenamer
            OWLEntityRenamer renamer = new OWLEntityRenamer(manager, Collections.singleton(source));
            List<OWLOntologyChange> changes = renamer.changeIRI(renameMap);
            manager.applyChanges(changes);
            
            // Add ALL axioms about the renamed entity to target
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

    /**
     * Build a map of parent class IRI → list of direct subclass IRIs.
     * Merges hierarchy from both source and target so the frontend can
     * cascade conflict resolutions from a parent class to all descendants.
     */
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

    /**
     * Build a map of parent property IRI → list of direct sub-property IRIs.
     */
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
        OWLOntology clone = manager.createOntology(original.getAxioms());
        for (OWLAnnotation annotation : original.getAnnotations()) {
            manager.applyChange(new AddOntologyAnnotation(clone, annotation));
        }
        for (OWLImportsDeclaration importDecl : original.getImportsDeclarations()) {
            manager.applyChange(new AddImport(clone, importDecl));
        }
        return clone;
    }

    /**
     * Build an annotation assertion index (subject IRI → axioms) in a single pass.
     */
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

    /**
     * Build a label index (entity IRI → label string) in a single pass.
     */
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

    /**
     * Lookup a label from the pre-built index, falling back to local name.
     */
    private String lookupLabel(Map<IRI, String> labelIndex, IRI entityIri) {
        String cached = labelIndex.get(entityIri);
        if (cached != null) return cached;
        String iri = entityIri.toString();
        String local = iri.contains("#") ? iri.substring(iri.lastIndexOf('#') + 1) : iri.substring(iri.lastIndexOf('/') + 1);
        return local.isBlank() ? iri : local;
    }
}
