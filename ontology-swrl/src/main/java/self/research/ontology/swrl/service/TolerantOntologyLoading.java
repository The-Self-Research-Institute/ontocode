package self.research.ontology.swrl.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLOntologyManager;

final class TolerantOntologyLoading {

    private static final int IMPORT_CONNECTION_TIMEOUT_MS = 5000;

    private TolerantOntologyLoading() {
    }

    static OWLOntologyManager manager() {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.setOntologyLoaderConfiguration(manager.getOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                .setConnectionTimeout(IMPORT_CONNECTION_TIMEOUT_MS));
        return manager;
    }
}
