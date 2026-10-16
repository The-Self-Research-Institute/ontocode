package self.research.ontology.reasoner.service;

import org.semanticweb.HermiT.Configuration;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;

final class LenientHermitReasonerFactory extends ReasonerFactory {

    @Override
    protected Configuration getProtegeConfiguration(OWLReasonerConfiguration config) {
        Configuration hermitConfig = config instanceof Configuration given
                ? given.clone()
                : super.getProtegeConfiguration(config);
        hermitConfig.ignoreUnsupportedDatatypes = true;
        return hermitConfig;
    }
}
