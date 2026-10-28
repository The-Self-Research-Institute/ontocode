package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;
import self.research.ontology.common.admission.WorkKind;

@Service
public class InferenceAdmission {

    private final HeavyWorkAdmission admission;

    public InferenceAdmission(HeavyWorkAdmission admission) {
        this.admission = admission;
    }

    public HeavyWorkAdmission.Hold admit(String userId, long estimatedTriples) {
        return admission.admitLoad(userId, WorkKind.INFERENCE_BUILD, estimatedTriples);
    }
}
