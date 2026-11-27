package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyChangeListener;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

final class EnactedChanges implements OWLOntologyChangeListener, AutoCloseable {

    private final ProjectOntologyCache cache;
    private final String projectId;
    private final OWLOntology ontology;
    private final OWLOntologyManager manager;
    private final List<OWLOntologyChange> changes = Collections.synchronizedList(new ArrayList<>());

    private EnactedChanges(ProjectOntologyCache cache, String projectId, OWLOntology ontology, OWLOntologyManager manager) {
        this.cache = cache;
        this.projectId = projectId;
        this.ontology = ontology;
        this.manager = manager;
    }

    static Optional<EnactedChanges> watch(ProjectOntologyCache cache, String projectId) {
        return cache.get(projectId).filter(entry -> entry.manager() != null).map(entry -> {
            EnactedChanges watcher = new EnactedChanges(cache, projectId, entry.ontology(), entry.manager());
            entry.manager().addOntologyChangeListener(watcher);
            return watcher;
        });
    }

    static boolean tooLargeToCompare(ProjectOntologyCache cache, String projectId) {
        return cache.get(projectId)
                .map(entry -> entry.ontology().getAxiomCount() > Limit.WHOLE_GRAPH_MAX_TRIPLES.value())
                .orElse(false);
    }

    @Override
    public void ontologiesChanged(List<? extends OWLOntologyChange> enacted) {
        for (OWLOntologyChange change : enacted) {
            if (change.getOntology() == ontology) {
                changes.add(change);
            }
        }
    }

    boolean nothingChanged() {
        return changes.isEmpty() && sameOntology();
    }

    boolean sameOntology() {
        return cache.get(projectId).map(entry -> entry.ontology() == ontology).orElse(false);
    }

    List<OWLOntologyChange> changes() {
        synchronized (changes) {
            return List.copyOf(changes);
        }
    }

    OWLOntology ontology() {
        return ontology;
    }

    OWLOntologyManager manager() {
        return manager;
    }

    @Override
    public void close() {
        manager.removeOntologyChangeListener(this);
    }
}
