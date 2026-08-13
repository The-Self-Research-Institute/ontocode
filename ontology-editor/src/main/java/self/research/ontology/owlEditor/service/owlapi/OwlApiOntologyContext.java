package self.research.ontology.owlEditor.service.owlapi;

import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.config.FastOpenCondition;
import self.research.ontology.owlEditor.service.OwlApiMutationCoordinator;

import java.util.Collections;
import java.util.Optional;
import java.util.function.BiFunction;

@Component
@Conditional(FastOpenCondition.class)
public class OwlApiOntologyContext {

    @Autowired
    private ProjectOntologyCache ontologyCache;

    @Autowired(required = false)
    private OwlApiMutationCoordinator mutationCoordinator;

    public boolean hasOntology(String projectId) {
        if (mutationCoordinator != null) {
            mutationCoordinator.ensureFreshForRead(projectId);
        }
        return ontologyCache.has(projectId);
    }

    public Optional<OWLOntology> ontology(String projectId) {
        if (!hasOntology(projectId)) {
            return Optional.empty();
        }
        return ontologyCache.get(projectId).map(c -> c.ontology());
    }

    public <T> T withOntology(String projectId, BiFunction<OWLOntology, OWLReasoner, T> fn, T emptyValue) {
        if (!hasOntology(projectId)) {
            return emptyValue;
        }
        return ontologyCache.get(projectId)
            .map(c -> fn.apply(c.ontology(), c.reasoner()))
            .orElse(emptyValue);
    }

}
