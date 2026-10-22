package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import self.research.ontology.owlEditor.service.delta.ExactDelta;

import java.util.Map;

public final class DocumentCarrier extends ExactCarrier {

    private final String store;
    private final String documentId;
    private final String before;
    private final String after;

    public DocumentCarrier(String store, String documentId, String before, String after) {
        super(new ExactDelta(new LinkedHashModel(), new LinkedHashModel()));
        this.store = store;
        this.documentId = documentId;
        this.before = before;
        this.after = after;
    }

    @Override
    public synchronized void stamp(Map<String, Object> changeData, String editId) {
        changeData.put("documentStore", store);
        changeData.put("documentId", documentId);
        if (before != null) {
            changeData.put("documentBefore", before);
        }
        if (after != null) {
            changeData.put("documentAfter", after);
        }
    }
}
