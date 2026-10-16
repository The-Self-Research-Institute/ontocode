package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Model;
import self.research.ontology.owlEditor.service.delta.CanonicalGraph;
import self.research.ontology.owlEditor.service.delta.DeltaCapture;
import self.research.ontology.owlEditor.service.delta.DeltaSparql;
import self.research.ontology.owlEditor.service.delta.ExactDelta;

import java.util.Optional;

@Slf4j
final class ExactOrigins {

    private ExactOrigins() {
    }

    static ChangeOrigin manualWithDelta(Model oldModel, Model newModel) {
        ChangeOrigin origin = ChangeOrigin.manual();
        try {
            return deltaBetween(oldModel, newModel).map(delta -> origin.withExact(new ExactCarrier(delta))).orElse(origin);
        } catch (RuntimeException e) {
            log.warn("[UNDO] Could not work out the exact change for the history: {}", e.getMessage());
            return origin;
        }
    }

    static boolean isUnchanged(Model oldModel, Model newModel) {
        return oldModel.size() == newModel.size() && org.eclipse.rdf4j.model.util.Models.isomorphic(oldModel, newModel);
    }

    static Optional<ExactDelta> deltaBetween(Model oldModel, Model newModel) {
        if (oldModel.size() > Limit.WHOLE_GRAPH_MAX_TRIPLES.value() || newModel.size() > Limit.WHOLE_GRAPH_MAX_TRIPLES.value()) {
            return Optional.empty();
        }
        if (!DeltaSparql.isRenderable(oldModel) || !DeltaSparql.isRenderable(newModel)) {
            return Optional.empty();
        }
        Model before = CanonicalGraph.canonicalise(oldModel);
        Model after = CanonicalGraph.canonicalise(newModel);
        ExactDelta delta = ExactDelta.between(before, after);
        return delta.isEmpty() || delta.isTooLarge() ? Optional.empty() : Optional.of(delta);
    }
}
