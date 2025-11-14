package self.research.ontology.owlEditor.plugin;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import java.util.List;
import java.util.Set;

/**
 * Interface for reasoner plugins.
 * Allows custom reasoning engines to be integrated.
 */
public interface ReasonerPlugin extends Plugin {

    /**
     * Create a reasoner instance for the given ontology
     */
    OWLReasoner createReasoner(OWLOntology ontology) throws PluginException;

    /**
     * Get reasoner capabilities
     */
    ReasonerCapabilities getCapabilities();

    /**
     * Reasoner capabilities
     */
    class ReasonerCapabilities {
        private boolean supportsClassification = true;
        private boolean supportsConsistencyChecking = true;
        private boolean supportsInstanceRetrieval = true;
        private boolean supportsPropertyHierarchy = true;
        private boolean supportsExplanations = false;
        private boolean supportsIncrementalReasoning = false;
        private String description;

        public boolean isSupportsClassification() { return supportsClassification; }
        public void setSupportsClassification(boolean supports) { this.supportsClassification = supports; }
        
        public boolean isSupportsConsistencyChecking() { return supportsConsistencyChecking; }
        public void setSupportsConsistencyChecking(boolean supports) { this.supportsConsistencyChecking = supports; }
        
        public boolean isSupportsInstanceRetrieval() { return supportsInstanceRetrieval; }
        public void setSupportsInstanceRetrieval(boolean supports) { this.supportsInstanceRetrieval = supports; }
        
        public boolean isSupportsPropertyHierarchy() { return supportsPropertyHierarchy; }
        public void setSupportsPropertyHierarchy(boolean supports) { this.supportsPropertyHierarchy = supports; }
        
        public boolean isSupportsExplanations() { return supportsExplanations; }
        public void setSupportsExplanations(boolean supports) { this.supportsExplanations = supports; }
        
        public boolean isSupportsIncrementalReasoning() { return supportsIncrementalReasoning; }
        public void setSupportsIncrementalReasoning(boolean supports) { this.supportsIncrementalReasoning = supports; }
        
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}