package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.service.delta.ExactDelta;

import java.util.Map;

public class ExactCarrier {

    private final String deleted;
    private final String inserted;
    private String carrierEditId;
    private int entries;

    public ExactCarrier(ExactDelta delta) {
        this.deleted = delta.deleted().isEmpty() ? null : ExactDelta.write(delta.deleted());
        this.inserted = delta.inserted().isEmpty() ? null : ExactDelta.write(delta.inserted());
    }

    public synchronized void stamp(Map<String, Object> changeData, String editId) {
        entries++;
        if (carrierEditId == null) {
            carrierEditId = editId;
            if (deleted != null) {
                changeData.put("exactDeleted", deleted);
            }
            if (inserted != null) {
                changeData.put("exactInserted", inserted);
            }
        } else {
            changeData.put("coveredBy", carrierEditId);
        }
    }

    public synchronized String carrierEditId() {
        return carrierEditId;
    }

    public synchronized int covers() {
        return Math.max(0, entries - 1);
    }
}
