package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads an OWL file into OWLAPI after Fuseki import completes and stores the
 * parsed model + structural reasoner in ProjectOntologyCache.
 *
 * Desktop-only — never active in cloud deployments.
 * Runs asynchronously on the owlParsingExecutor thread pool so it doesn't
 * block the import response.
 */
@Service
@ConditionalOnProperty(name = "ontocode.desktop.mode", havingValue = "true")
public class DesktopOntologyLoader {

    private static final Logger log = LoggerFactory.getLogger(DesktopOntologyLoader.class);

    @Autowired
    private ProjectOntologyCache cache;

    @Autowired
    private StorageManager storageManager;

    // Tracks projects currently being loaded to prevent duplicate async loads
    private final Set<String> loadingInProgress = ConcurrentHashMap.newKeySet();

    /**
     * Trigger async load if the project isn't already cached or loading.
     * Call this on first hierarchy access so existing projects benefit from
     * OWLAPI without needing a re-import.
     */
    public void triggerLazyLoadIfNeeded(String projectId) {
        if (cache.has(projectId) || loadingInProgress.contains(projectId)) return;
        // Prefer original file (OFN/TTL) — 3-5x faster for OWLAPI than converted RDF/XML
        Optional<Path> source = findFastestParseSource(projectId);
        source.ifPresent(path -> {
            loadingInProgress.add(projectId);
            loadAndCacheAsync(projectId, path);
        });
    }

    /**
     * Returns the ontology file that OWLAPI can parse fastest.
     * Priority: .ofn > .ttl > .owl (RDF/XML) — OFN is the native OWLAPI format.
     */
    private Optional<Path> findFastestParseSource(String projectId) {
        Path dir = storageManager.projectDir(projectId);
        List<String> fastFirst = List.of(
            "ontology.original.ofn",
            "ontology.original.ttl",
            "ontology.original.nt",
            "ontology.current.ttl",
            "ontology.original.owl",
            "ontology.current.owl"
        );
        for (String name : fastFirst) {
            Path p = dir.resolve(name);
            if (java.nio.file.Files.exists(p)) {
                log.info("[Desktop] Using {} for OWLAPI parse (fastest available)", name);
                return Optional.of(p);
            }
        }
        return storageManager.findCurrentOntology(projectId);
    }

    /**
     * Asynchronously loads the OWL file at owlFilePath into OWLAPI and caches
     * the model + structural reasoner for instant hierarchy navigation.
     *
     * Called after Fuseki bulk-load completes (phase 5 persist-copy).
     */
    @Async("owlParsingExecutor")
    public void loadAndCacheAsync(String projectId, Path owlFilePath) {
        if (!owlFilePath.toFile().exists()) {
            log.warn("[Desktop] OWL file not found, skipping OWLAPI cache: {}", owlFilePath);
            return;
        }

        long fileSizeMb = owlFilePath.toFile().length() / (1024 * 1024);

        // Check if JVM has enough free heap to load this file into OWLAPI.
        // OWLAPI typically needs 3-4x the file size in heap.
        // Leave at least 400MB headroom for Spring Boot + Fuseki queries.
        Runtime rt = Runtime.getRuntime();
        long freeHeapMb = (rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) / (1024 * 1024);
        long requiredMb = fileSizeMb * 4 + 400;
        if (freeHeapMb < requiredMb) {
            log.info("[Desktop] Skipping OWLAPI cache — file {} MB needs ~{} MB free heap, only {} MB available",
                fileSizeMb, requiredMb, freeHeapMb);
            return;
        }

        long start = System.currentTimeMillis();
        log.info("[Desktop] Loading OWLAPI model for project {} from {} ({} MB)", projectId, owlFilePath, fileSizeMb);

        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();

            // Silent imports — we load only what's in the file.
            // Desktop import dialog (future) will handle explicit import resolution.
            manager.setOntologyLoaderConfiguration(
                new OWLOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                    .setLoadAnnotationAxioms(true)
            );

            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(owlFilePath.toFile());
            long classCount = ontology.classesInSignature().count();
            log.info("[Desktop] OWLAPI parsed {} classes in {}ms", classCount,
                System.currentTimeMillis() - start);

            // Structural reasoner: no inference, just asserted hierarchy traversal.
            // getSubClasses() on StructuralReasoner is O(1) — pure pointer lookup.
            OWLReasoner reasoner = new StructuralReasonerFactory().createNonBufferingReasoner(ontology);
            reasoner.precomputeInferences(); // pre-index asserted hierarchy

            cache.put(projectId, ontology, reasoner, manager);
            loadingInProgress.remove(projectId);
            log.info("[Desktop] OWLAPI model + reasoner cached for project {} in {}ms total",
                projectId, System.currentTimeMillis() - start);

        } catch (Exception e) {
            log.warn("[Desktop] Failed to cache OWLAPI model for project {}: {}", projectId, e.getMessage());
            // Non-fatal: hierarchy falls back to Fuseki SPARQL
        }
    }
}
