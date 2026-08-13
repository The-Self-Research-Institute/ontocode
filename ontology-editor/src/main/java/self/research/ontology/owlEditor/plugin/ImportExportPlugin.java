package self.research.ontology.owlEditor.plugin;

import org.semanticweb.owlapi.model.OWLOntology;

import java.io.InputStream;
import java.io.OutputStream;

public interface ImportExportPlugin extends Plugin {

    FormatInfo getFormatInfo();

    boolean canImport();

    boolean canExport();

    OWLOntology importOntology(InputStream input) throws PluginException;

    void exportOntology(OWLOntology ontology, OutputStream output) throws PluginException;

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