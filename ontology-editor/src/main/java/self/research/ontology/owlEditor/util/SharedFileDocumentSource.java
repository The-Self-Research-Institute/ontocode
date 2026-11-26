package self.research.ontology.owlEditor.util;

import org.semanticweb.owlapi.io.FileDocumentSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class SharedFileDocumentSource extends FileDocumentSource {

    private final Path path;

    private SharedFileDocumentSource(Path path) {
        super(path.toFile());
        this.path = path;
    }

    public static SharedFileDocumentSource of(Path path) {
        return new SharedFileDocumentSource(path);
    }

    @Override
    public Optional<InputStream> getInputStream() {
        try {
            return Optional.of(Files.newInputStream(path));
        } catch (IOException e) {
            failedOnStreams.set(true);
            return Optional.empty();
        }
    }
}
