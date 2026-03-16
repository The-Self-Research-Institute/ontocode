package self.research.ontology.owlEditor.plugin;

import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;

/**
 * ELK Reasoner Plugin
 * Provides ELK reasoning capabilities (fast, scalable EL reasoner)
 * Using elk-owlapi version 0.4.3 compatible with OWL API 5.x
 */
public class ELKReasonerPlugin implements ReasonerPlugin {

    private static final Logger log = LoggerFactory.getLogger(ELKReasonerPlugin.class);
    private PluginMetadata metadata;
    private ElkReasonerFactory reasonerFactory;

    public ELKReasonerPlugin() {
        initializeMetadata();
    }

    private void initializeMetadata() {
        metadata = new PluginMetadata();
        metadata.setId("elk-reasoner");
        metadata.setName("ELK Reasoner");
        metadata.setVersion("0.4.3");
        metadata.setDescription("Fast and scalable EL reasoner. Optimized for large ontologies with EL expressivity.");
        metadata.setAuthor("OntoCode Team");
        metadata.setAuthorEmail("support@ontocode.dev");
        metadata.setWebsite("https://github.com/liveontologies/elk-reasoner");
        metadata.setType(PluginMetadata.PluginType.REASONER);
        metadata.setEnabled(true);
        metadata.setTags(Arrays.asList("reasoner", "elk", "fast", "scalable", "el"));
        metadata.setIconUrl("/icons/elk-icon.png");
        metadata.setSettings(new HashMap<>());
    }

    @Override
    public PluginMetadata getMetadata() {
        return metadata;
    }

    @Override
    public void initialize() throws PluginException {
        try {
            reasonerFactory = new ElkReasonerFactory();
            log.info("ELK Reasoner Plugin initialized (version 0.4.3)");
        } catch (Exception e) {
            throw new PluginException("Failed to initialize ELK reasoner", e);
        }
    }

    @Override
    public void shutdown() throws PluginException {
        reasonerFactory = null;
        log.info("ELK Reasoner Plugin shutdown");
    }

    @Override
    public boolean isCompatible(String editorVersion) {
        // Compatible with all versions 1.x.x
        return editorVersion.startsWith("1.");
    }

    @Override
    public OWLReasoner createReasoner(OWLOntology ontology) throws PluginException {
        if (reasonerFactory == null) {
            throw new PluginException("ELK reasoner not initialized");
        }

        try {
            OWLReasoner reasoner = reasonerFactory.createReasoner(ontology);
            log.debug("Created ELK reasoner for ontology: {}", ontology.getOntologyID());
            return reasoner;
        } catch (Exception e) {
            throw new PluginException("Failed to create ELK reasoner", e);
        }
    }

    @Override
    public ReasonerCapabilities getCapabilities() {
        ReasonerCapabilities capabilities = new ReasonerCapabilities();
        capabilities.setSupportsClassification(true);
        capabilities.setSupportsConsistencyChecking(true);
        capabilities.setSupportsInstanceRetrieval(true);
        capabilities.setSupportsPropertyHierarchy(true);
        capabilities.setSupportsExplanations(false);
        capabilities.setSupportsIncrementalReasoning(true);
        capabilities.setDescription(
            "ELK is a very fast reasoner optimized for EL profile. " +
            "Best for large ontologies with simple relationships."
        );
        return capabilities;
    }

    @Override
    public String getConfigurationSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "numberOfWorkers": {
                  "type": "integer",
                  "description": "Number of worker threads",
                  "default": 4,
                  "minimum": 1,
                  "maximum": 32
                },
                "incrementalMode": {
                  "type": "boolean",
                  "description": "Enable incremental reasoning",
                  "default": true
                }
              }
            }
            """;
    }
}