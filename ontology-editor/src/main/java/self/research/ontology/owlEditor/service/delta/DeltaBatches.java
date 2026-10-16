package self.research.ontology.owlEditor.service.delta;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;

import java.util.ArrayList;
import java.util.List;

public final class DeltaBatches {

    private DeltaBatches() {
    }

    public static List<ExactDelta> split(ExactDelta delta, int maxTriples) {
        if (delta.deleted().size() + delta.inserted().size() <= maxTriples) {
            return List.of(delta);
        }
        List<ExactDelta> batches = new ArrayList<>();
        for (Model part : pack(delta.deleted(), maxTriples)) {
            batches.add(new ExactDelta(part, new LinkedHashModel()));
        }
        for (Model part : pack(delta.inserted(), maxTriples)) {
            batches.add(new ExactDelta(new LinkedHashModel(), part));
        }
        return batches;
    }

    private static List<Model> pack(Model model, int maxTriples) {
        List<Model> batches = new ArrayList<>();
        Model current = new LinkedHashModel();
        for (Statement statement : model) {
            if (DeltaSparql.touchesBlankNode(statement)) {
                continue;
            }
            if (current.size() >= maxTriples) {
                batches.add(current);
                current = new LinkedHashModel();
            }
            current.add(statement);
        }
        for (Model component : DeltaSparql.components(model).values()) {
            if (!current.isEmpty() && current.size() + component.size() > maxTriples) {
                batches.add(current);
                current = new LinkedHashModel();
            }
            current.addAll(component);
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }
}
