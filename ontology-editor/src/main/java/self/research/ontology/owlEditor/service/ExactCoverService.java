package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;

@Service
public class ExactCoverService {

    private final HistoryChangeRepository history;

    public ExactCoverService(HistoryChangeRepository history) {
        this.history = history;
    }

    public void record(String projectId, ExactCarrier carrier) {
        if (carrier.carrierEditId() == null || carrier.covers() == 0) {
            return;
        }
        history.setExactCovers(projectId, carrier.carrierEditId(), carrier.covers());
    }
}
