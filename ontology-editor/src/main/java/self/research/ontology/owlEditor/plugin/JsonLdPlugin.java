package self.research.ontology.owlEditor.plugin;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFJsonLDDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;

/**
 * JSON-LD Import/Export Plugin
 * Supports JSON-LD format for ontology import and export
 */
public class JsonLdPlugin implements ImportExportPlugin {

    private static final Logger log = LoggerFactory.getLogger(JsonLdPlugin.class);
    private PluginMetadata metadata;
    private FormatInfo formatInfo;

    public JsonLdPlugin() {
        initializeMetadata();
        initializeFormatInfo();
    }

    private void initializeMetadata() {
        metadata = new PluginMetadata();
        metadata.setId("jsonld-plugin");
        metadata.setName("JSON-LD Import/Export");
        metadata.setVersion("1.0.0");
        metadata.setDescription("Import and export ontologies in JSON-LD format. " +
            "JSON-LD is a JSON-based format for representing linked data.");
        metadata.setAuthor("OntoCode Team");
        metadata.setAuthorEmail("support@ontocode.dev");
        metadata.setWebsite("https://json-ld.org/");
        metadata.setType(PluginMetadata.PluginType.IMPORT_EXPORT);
        metadata.setEnabled(true);
        metadata.setTags(Arrays.asList("json-ld", "import", "export", "format", "linked-data"));
        metadata.setIconUrl("/icons/jsonld-icon.png");
        metadata.setSettings(new HashMap<>());
    }

    private void initializeFormatInfo() {
        formatInfo = new FormatInfo(
            "JSON-LD",
            ".jsonld",
            "application/ld+json",
            "JSON-LD (JSON for Linking Data) format"
        );
        formatInfo.setStandard(true);
    }

    @Override
    public PluginMetadata getMetadata() {
        return metadata;
    }

    @Override
    public void initialize() throws PluginException {
        log.info("JSON-LD Plugin initialized");
    }

    @Override
    public void shutdown() throws PluginException {
        log.info("JSON-LD Plugin shutdown");
    }

    @Override
    public boolean isCompatible(String editorVersion) {
        return editorVersion.startsWith("1.");
    }

    @Override
    public FormatInfo getFormatInfo() {
        return formatInfo;
    }

    @Override
    public boolean canImport() {
        return true;
    }

    @Override
    public boolean canExport() {
        return true;
    }

    @Override
    public OWLOntology importOntology(InputStream input) throws PluginException {
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(input);
            log.info("Imported ontology from JSON-LD format");
            return ontology;
        } catch (Exception e) {
            throw new PluginException("Failed to import ontology from JSON-LD", e);
        }
    }

    @Override
    public void exportOntology(OWLOntology ontology, OutputStream output) throws PluginException {
        try {
            OWLOntologyManager manager = ontology.getOWLOntologyManager();
            RDFJsonLDDocumentFormat format = new RDFJsonLDDocumentFormat();
            manager.saveOntology(ontology, format, output);
            log.info("Exported ontology to JSON-LD format");
        } catch (Exception e) {
            throw new PluginException("Failed to export ontology to JSON-LD", e);
        }
    }

    @Override
    public String getConfigurationSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "prettyPrint": {
                  "type": "boolean",
                  "description": "Pretty print JSON output",
                  "default": true
                },
                "useCompactForm": {
                  "type": "boolean",
                  "description": "Use compact JSON-LD form",
                  "default": false
                }
              }
            }
            """;
    }
}