package self.research.ontology.owlEditor.service.infer;

import org.springframework.stereotype.Service;
import self.research.ontology.common.admission.WorkKind;
import self.research.ontology.owlEditor.service.BusyException;
import self.research.ontology.owlEditor.service.HeavyWorkAdmission;

@Service
public class LedgerInferenceAdmission implements InferenceAdmission {

    private final HeavyWorkAdmission admission;
    private final InferenceCapacity capacity;

    public LedgerInferenceAdmission(HeavyWorkAdmission admission, InferenceCapacity capacity) {
        this.admission = admission;
        this.capacity = capacity;
    }

    @Override
    public Permit admit(String userId, long estimatedTriples) {
        long triples = estimatedTriples > 0 ? estimatedTriples : capacity.maxAssertedTriples();
        HeavyWorkAdmission.Hold hold;
        try {
            hold = admission.admitLoad(userId, WorkKind.INFERENCE_BUILD, triples);
        } catch (BusyException e) {
            throw new InferenceException(InferenceException.BUSY,
                    "The server is busy with other heavy work. Try computing the inferred view again shortly.");
        }
        return hold::close;
    }

    @Override
    public boolean isBusy() {
        return admission.enabled() && !admission.hasCapacityFor(admission.estimates().loadAndReason(capacity.maxAssertedTriples()));
    }
}
