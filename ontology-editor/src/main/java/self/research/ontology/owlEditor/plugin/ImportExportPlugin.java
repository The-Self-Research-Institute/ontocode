package self.research.ontology.owlEditor.plugin;

import org.semanticweb.owlapi.model.OWLOntology;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Interface for import/export plugins.
 * Allows custom format support.
 */
public interface ImportExportPlugin extends Plugin {

    /**
     * Get supported format
     */
    FormatInfo getFormatInfo();

    /**
     * Check if can import
     */
    boolean canImport();

    /**
     * Check if can export
     */
    boolean canExport();

    /**
     * Import ontology from input stream
     */
    OWLOntology importOntology(InputStream input) throws PluginException;

    /**
     * Export ontology to output stream
     */
    void exportOntology(OWLOntology ontology, OutputStream output) throws PluginException;

    /**
     * Format information
     */
    class FormatInfo {
        private String formatName;
        private String fileExtension;
        private String mimeType;
        private String description;
        private boolean isStandard;

        public FormatInfo(String name, String extension, String mime, String description) {
            this.formatName = name;
            this.fileExtension = extension;
            this.mimeType = mime;
            this.description = description;
        }

        public String getFormatName() { return formatName; }
        public String getFileExtension() { return fileExtension; }
        public String getMimeType() { return mimeType; }
        public String getDescription() { return description; }
        public boolean isStandard() { return isStandard; }
        public void setStandard(boolean standard) { isStandard = standard; }
    }
}