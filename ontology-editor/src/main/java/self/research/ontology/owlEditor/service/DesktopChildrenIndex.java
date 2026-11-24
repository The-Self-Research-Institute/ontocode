package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.model.OWLClass;
import self.research.ontology.owlEditor.dto.OntologyDto;
import self.research.ontology.owlEditor.hierarchy.HierarchySnapshotBuilder;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyChangeListener;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

final class DesktopChildrenIndex {

    static final int MAX_PROJECTS = 4;

    record Snapshot(long version, Map<OWLClass, Set<OWLClass>> index, Set<OWLClass> roots) {}

    private static final class Holder implements OWLOntologyChangeListener {
        private final WeakReference<OWLOntology> owner;
        private final WeakReference<OWLOntologyManager> manager;
        private final AtomicLong version = new AtomicLong();
        private final Map<Imports, Snapshot> snapshots = new ConcurrentHashMap<>();

        Holder(OWLOntology ontology, OWLOntologyManager manager) {
            this.owner = new WeakReference<>(ontology);
            this.manager = new WeakReference<>(manager);
        }

        @Override
        public void ontologiesChanged(List<? extends OWLOntologyChange> changes) {
            if (owner.get() != null && !changes.isEmpty()) {
                version.incrementAndGet();
                snapshots.clear();
            }
        }
    }

    private static final Map<String, Holder> HOLDERS = new LinkedHashMap<>(8, 0.75f, true);

    static List<OntologyDto.TreeNode> topLevel(HierarchySnapshotBuilder builder, String projectId, OWLOntology ontology,
                                        int limit, int offset, Imports scope) {
        Snapshot snapshot = snapshot(builder, projectId, ontology, scope);
        return builder.buildTopLevelAsserted(ontology, limit, offset, scope, snapshot.index(), snapshot.roots());
    }

    static int topLevelTotal(HierarchySnapshotBuilder builder, String projectId, OWLOntology ontology, Imports scope) {
        return snapshot(builder, projectId, ontology, scope).roots().size();
    }

    static List<OntologyDto.TreeNode> children(HierarchySnapshotBuilder builder, String projectId, OWLOntology ontology,
                                        OWLReasoner reasoner, String parentIri, int limit, int offset, Imports scope) {
        return builder.buildChildren(ontology, reasoner, parentIri, limit, offset, scope,
                snapshot(builder, projectId, ontology, scope).index());
    }

    private static Snapshot snapshot(HierarchySnapshotBuilder builder, String projectId, OWLOntology ontology, Imports scope) {
        return get(projectId, ontology, scope, o -> new Snapshot(0, builder.assertedChildrenIndex(o, scope),
                builder.assertedTopLevelCandidates(o, scope)));
    }

    static Snapshot get(String projectId, OWLOntology ontology, Imports scope, Function<OWLOntology, Snapshot> build) {
        Holder holder = holderFor(projectId, ontology);
        long before = holder.version.get();
        Snapshot cached = holder.snapshots.get(scope);
        if (cached != null && cached.version() == before) {
            return cached;
        }
        Snapshot built = build.apply(ontology);
        Snapshot stamped = new Snapshot(before, built.index(), built.roots());
        if (holder.version.get() == before) {
            holder.snapshots.put(scope, stamped);
        }
        return stamped;
    }

    private static synchronized Holder holderFor(String projectId, OWLOntology ontology) {
        Holder holder = HOLDERS.get(projectId);
        if (holder != null && holder.owner.get() == ontology) {
            return holder;
        }
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        if (holder != null) {
            drop(holder);
        }
        holder = new Holder(ontology, manager);
        manager.addOntologyChangeListener(holder);
        HOLDERS.put(projectId, holder);
        while (HOLDERS.size() > MAX_PROJECTS) {
            String eldest = HOLDERS.keySet().iterator().next();
            drop(HOLDERS.remove(eldest));
        }
        return holder;
    }

    private static void drop(Holder holder) {
        OWLOntologyManager manager = holder.manager.get();
        if (manager != null) {
            manager.removeOntologyChangeListener(holder);
        }
        holder.snapshots.clear();
    }
}
