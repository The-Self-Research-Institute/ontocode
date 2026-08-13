package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.document.HierarchySnapshotDoc;
import self.research.ontology.owlEditor.dto.OntologyDto;
import self.research.ontology.owlEditor.hierarchy.HierarchyAlgorithmVersion;
import self.research.ontology.owlEditor.repository.HierarchySnapshotRepository;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;

@Service
public class HierarchyIndexService {

    private static final Logger log = LoggerFactory.getLogger(HierarchyIndexService.class);

    private final HierarchySnapshotRepository snapshotRepository;
    private final HierarchySnapshotBuildService buildService;
    private final Executor hierarchyIndexExecutor;

    @Value("${ontocode.hierarchy.snapshot.enabled:true}")
    private boolean snapshotEnabled;

    @Value("${ontocode.hierarchy.snapshot.legacy-sparql-fallback:false}")
    private boolean legacySparqlFallback;

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    @Autowired(required = false) @Nullable
    private ProjectOntologyCache ontologyCache;

    public HierarchyIndexService(HierarchySnapshotRepository snapshotRepository,
                                 HierarchySnapshotBuildService buildService,
                                 @Qualifier("hierarchyIndexExecutor") Executor hierarchyIndexExecutor) {
        this.snapshotRepository = snapshotRepository;
        this.buildService = buildService;
        this.hierarchyIndexExecutor = hierarchyIndexExecutor;
    }

    public boolean isEnabled() {
        return snapshotEnabled;
    }

    public boolean allowsLegacySparqlFallback() {
        return legacySparqlFallback;
    }

    public Optional<HierarchySnapshotDoc> find(String projectId) {
        return snapshotRepository.findById(projectId);
    }

    public boolean isReady(String projectId) {
        return snapshotRepository.findById(projectId)
                .filter(d -> d.getStatus() == HierarchySnapshotDoc.Status.READY)
                .filter(d -> HierarchyAlgorithmVersion.CURRENT.equals(d.getAlgorithmVersion()))
                .isPresent();
    }

    public Map<String, Object> statusPayload(String projectId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("hierarchySnapshotEnabled", snapshotEnabled);

        snapshotRepository.findById(projectId).ifPresentOrElse(doc -> {
            body.put("hierarchyReady", doc.getStatus() == HierarchySnapshotDoc.Status.READY);
            body.put("hierarchyStatus", doc.getStatus().name());
            body.put("hierarchyEngine", doc.getStatus() == HierarchySnapshotDoc.Status.READY ? "snapshot" : "none");
            body.put("hierarchyAlgorithmVersion", doc.getAlgorithmVersion());
            body.put("hierarchyRevision", doc.getRevision());
            body.put("hierarchyBuiltAt", doc.getBuiltAt());
            if (doc.getErrorMessage() != null) {
                body.put("hierarchyError", doc.getErrorMessage());
            }
            if (doc.getMeta() != null) {
                body.putAll(doc.getMeta());
            }
        }, () -> {
            body.put("hierarchyReady", false);
            body.put("hierarchyStatus", "MISSING");
            body.put("hierarchyEngine", "none");
        });

        return body;
    }

    public Optional<Map<String, Object>> topLevelResponse(String projectId, int limit) {
        return snapshotRepository.findById(projectId)
                .filter(d -> d.getStatus() == HierarchySnapshotDoc.Status.READY)
                .filter(d -> HierarchyAlgorithmVersion.CURRENT.equals(d.getAlgorithmVersion()))
                .map(doc -> {
                    List<OntologyDto.TreeNode> nodes = doc.getTopLevelNodes() != null
                            ? doc.getTopLevelNodes() : List.of();
                    int effectiveLimit = Math.max(1, limit);
                    List<OntologyDto.TreeNode> slice = nodes.size() > effectiveLimit
                            ? nodes.subList(0, effectiveLimit) : nodes;
                    int total = doc.getTopLevelTotal();

                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("success", true);
                    body.put("classes", slice);
                    body.put("topLevelReturned", slice.size());
                    body.put("topLevelTotal", total);
                    body.put("topLevelLimit", effectiveLimit);
                    body.put("truncated", total > effectiveLimit);
                    body.put("hierarchyEngine", "snapshot");
                    body.put("hierarchyReady", true);
                    if (doc.getMeta() != null) {
                        body.putAll(doc.getMeta());
                    }
                    return body;
                });
    }

    public Optional<List<OntologyDto.TreeNode>> children(String projectId, String parentIri, int limit, int offset) {
        return snapshotRepository.findById(projectId)
                .filter(d -> d.getStatus() == HierarchySnapshotDoc.Status.READY)
                .map(doc -> {
                    Map<String, List<OntologyDto.TreeNode>> index = doc.getChildrenByParent();
                    if (index == null) {
                        return List.<OntologyDto.TreeNode>of();
                    }
                    List<OntologyDto.TreeNode> all = index.getOrDefault(parentIri, List.of());
                    return all.stream()
                            .skip(Math.max(0, offset))
                            .limit(Math.max(1, limit))
                            .toList();
                });
    }

    public void scheduleBuild(String projectId) {
        if (!snapshotEnabled || !buildService.isEnabled()) {
            return;
        }

        if (desktopMode && ontologyCache != null && ontologyCache.has(projectId)) {
            return;
        }
        String revision = Instant.now().toString();
        log.info("[HierarchyIndex] Scheduling snapshot build for {}", projectId);

        hierarchyIndexExecutor.execute(() -> buildService.buildAndStore(projectId, revision));
    }

    public void markStale(String projectId) {
        snapshotRepository.findById(projectId).ifPresent(doc -> {
            doc.setStatus(HierarchySnapshotDoc.Status.STALE);
            snapshotRepository.save(doc);
        });
        scheduleBuild(projectId);
    }

    public void evict(String projectId) {
        snapshotRepository.deleteById(projectId);
        log.info("[HierarchyIndex] Evicted snapshot for {}", projectId);
    }
}
