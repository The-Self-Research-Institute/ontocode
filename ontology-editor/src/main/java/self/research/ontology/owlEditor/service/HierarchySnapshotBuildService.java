package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.HierarchySnapshotDoc;
import self.research.ontology.owlEditor.dto.OntologyDto;
import self.research.ontology.owlEditor.hierarchy.HierarchyAlgorithmVersion;
import self.research.ontology.owlEditor.hierarchy.HierarchySnapshotBuilder;
import self.research.ontology.owlEditor.hierarchy.OntologyMetricsComputer;
import self.research.ontology.owlEditor.repository.HierarchySnapshotRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
public class HierarchySnapshotBuildService {

    private static final Logger log = LoggerFactory.getLogger(HierarchySnapshotBuildService.class);
    private static final int TOP_LEVEL_STORE_LIMIT = 50_000;

    private final StorageManager storageManager;
    private final HierarchySnapshotBuilder snapshotBuilder;
    private final OntologyMetricsComputer metricsComputer;
    private final HierarchySnapshotRepository snapshotRepository;
    private final ProjectMetadataService projectMetadataService;

    @Value("${ontocode.hierarchy.snapshot.enabled:true}")
    private boolean snapshotEnabled;

    public HierarchySnapshotBuildService(StorageManager storageManager,
                                         HierarchySnapshotBuilder snapshotBuilder,
                                         OntologyMetricsComputer metricsComputer,
                                         HierarchySnapshotRepository snapshotRepository,
                                         ProjectMetadataService projectMetadataService) {
        this.storageManager = storageManager;
        this.snapshotBuilder = snapshotBuilder;
        this.metricsComputer = metricsComputer;
        this.snapshotRepository = snapshotRepository;
        this.projectMetadataService = projectMetadataService;
    }

    public boolean isEnabled() {
        return snapshotEnabled;
    }

    public void buildAndStore(String projectId, String revision) {
        if (!snapshotEnabled) {
            return;
        }

        HierarchySnapshotDoc building = HierarchySnapshotDoc.building(projectId, revision);
        snapshotRepository.save(building);

        OWLOntologyManager manager = null;
        OWLOntology ontology = null;
        OWLReasoner reasoner = null;

        try {
            Optional<Path> owlPath = findFastestParseSource(projectId);
            if (owlPath.isEmpty()) {
                fail(projectId, "No OWL file found for hierarchy index");
                return;
            }

            Path path = owlPath.get();
            long fileSizeMb = Files.size(path) / (1024 * 1024);
            Runtime rt = Runtime.getRuntime();
            long maxHeapMb = rt.maxMemory() / (1024 * 1024);
            long estimatedModelMb = Math.max(64, fileSizeMb * 3);
            if (estimatedModelMb > maxHeapMb - 512) {
                fail(projectId, "Ontology too large for in-memory hierarchy index on this node ("
                        + fileSizeMb + " MB file, " + maxHeapMb + " MB heap)");
                return;
            }

            long start = System.currentTimeMillis();
            manager = OWLManager.createOWLOntologyManager();
            manager.setOntologyLoaderConfiguration(
                    new OWLOntologyLoaderConfiguration()
                            .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                            .setLoadAnnotationAxioms(true));

            manager.addIRIMapper(iri -> {
                String s = iri.toString();
                if (s.startsWith("http://") || s.startsWith("https://")) {
                    return org.semanticweb.owlapi.model.IRI.create(
                            "file:///intentionally-missing-import-" + Math.abs(s.hashCode()));
                }
                return null;
            });
            ontology = manager.loadOntologyFromOntologyDocument(path.toFile());
            reasoner = new StructuralReasonerFactory().createNonBufferingReasoner(ontology);
            reasoner.precomputeInferences();

            int topLevelTotal = snapshotBuilder.countTopLevelCandidates(ontology, reasoner);
            List<OntologyDto.TreeNode> topLevel =
                    snapshotBuilder.buildTopLevel(ontology, reasoner, TOP_LEVEL_STORE_LIMIT, 0);
            Map<String, List<OntologyDto.TreeNode>> childrenIndex =
                    snapshotBuilder.buildChildrenIndex(ontology, reasoner);
            Map<String, Object> meta = new LinkedHashMap<>(metricsComputer.compute(ontology, reasoner));
            meta.put("hierarchyEngine", "snapshot");
            meta.put("hierarchyAlgorithmVersion", HierarchyAlgorithmVersion.CURRENT);
            meta.put("hierarchyBuiltAt", java.time.Instant.now().toString());
            meta.put("hierarchyBuildMs", System.currentTimeMillis() - start);

            HierarchySnapshotDoc doc = snapshotRepository.findById(projectId).orElse(building);
            doc.setTopLevelNodes(topLevel);
            doc.setTopLevelTotal(topLevelTotal);
            doc.setTopLevelComputedLimit(TOP_LEVEL_STORE_LIMIT);
            doc.setChildrenByParent(childrenIndex);
            doc.setMeta(meta);
            doc.setAlgorithmVersion(HierarchyAlgorithmVersion.CURRENT);
            doc.setRevision(revision);
            doc.markReady();
            snapshotRepository.save(doc);

            mergeMetaIntoProject(projectId, meta);

            log.info("[HierarchyIndex] READY project={} topLevel={}/{} childrenParents={} in {}ms",
                    projectId, topLevel.size(), topLevelTotal, childrenIndex.size(),
                    System.currentTimeMillis() - start);

        } catch (OutOfMemoryError oom) {
            log.warn("[HierarchyIndex] OOM building snapshot for {}", projectId);
            fail(projectId, "Out of memory building hierarchy index");
        } catch (Exception e) {
            log.error("[HierarchyIndex] Failed for {}: {}", projectId, e.getMessage(), e);
            fail(projectId, e.getMessage() != null ? e.getMessage() : "build failed");
        } finally {
            if (reasoner != null) {
                try { reasoner.dispose(); } catch (Exception ignored) {}
            }
            if (manager != null && ontology != null) {
                try { manager.removeOntology(ontology); } catch (Exception ignored) {}
            }
        }
    }

    private void mergeMetaIntoProject(String projectId, Map<String, Object> meta) {
        try {
            Map<String, Object> existing = projectMetadataService.readMeta(projectId).orElse(new HashMap<>());
            Map<String, Object> merged = new HashMap<>(existing);
            merged.putAll(meta);
            merged.put("hierarchyReady", true);
            projectMetadataService.writeMeta(projectId, merged);
        } catch (Exception e) {
            log.warn("[HierarchyIndex] Could not merge meta for {}: {}", projectId, e.getMessage());
        }
    }

    private void fail(String projectId, String message) {
        HierarchySnapshotDoc doc = snapshotRepository.findById(projectId).orElse(new HierarchySnapshotDoc());
        doc.setProjectId(projectId);
        doc.setStatus(HierarchySnapshotDoc.Status.FAILED);
        doc.setErrorMessage(message);
        doc.setBuiltAt(System.currentTimeMillis());
        snapshotRepository.save(doc);
        try {
            Map<String, Object> existing = projectMetadataService.readMeta(projectId).orElse(new HashMap<>());
            Map<String, Object> merged = new HashMap<>(existing);
            merged.put("hierarchyReady", false);
            merged.put("hierarchyError", message);
            projectMetadataService.writeMeta(projectId, merged);
        } catch (Exception ignored) {}
    }

    private Optional<Path> findFastestParseSource(String projectId) {
        Path dir = storageManager.projectDir(projectId);

        Path dirtyMarker = dir.resolve("ontology.dirty");
        if (Files.exists(dirtyMarker)) {
            try {
                Path fresh = storageManager.exportOntology(projectId, "rdfxml");
                for (String stale : List.of("ontology.original.ofn", "ontology.original.ttl",
                        "ontology.original.nt", "ontology.current.ttl", "ontology.current.owl")) {
                    Path p = dir.resolve(stale);
                    if (!p.equals(fresh)) {
                        Files.deleteIfExists(p);
                    }
                }
                Files.deleteIfExists(dirtyMarker);
                log.info("[HierarchyIndex] Project {} had post-import mutations — parsed source re-exported from Fuseki: {}",
                        projectId, fresh);
                return Optional.of(fresh);
            } catch (Exception e) {
                log.warn("[HierarchyIndex] Fresh Fuseki export failed for dirty project {} — trying on-disk copy: {}",
                        projectId, e.getMessage());
                try {
                    Files.deleteIfExists(dirtyMarker);
                } catch (java.io.IOException ignored) {
                }
            }
        }

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
            if (Files.exists(p)) {
                return Optional.of(p);
            }
        }
        return storageManager.findCurrentOntology(projectId);
    }
}
