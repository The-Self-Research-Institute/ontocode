package self.research.ontology.owlEditor.util;

import org.eclipse.rdf4j.rio.RDFFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class RdfFiles {

    private RdfFiles() {
    }

    public static RDFFormat snapshotFormat(Path snapshot) {
        return snapshot.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nt")
                ? RDFFormat.NTRIPLES : RDFFormat.RDFXML;
    }

    public static long sizeOrUnknown(Path file) {
        try {
            return file != null && Files.exists(file) ? Files.size(file) : -1L;
        } catch (IOException e) {
            return -1L;
        }
    }
}
