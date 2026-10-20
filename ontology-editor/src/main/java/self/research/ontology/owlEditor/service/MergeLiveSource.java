package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.ManchesterSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Component
public class MergeLiveSource {

    private final StorageManager storageManager;
    private final SparqlDatasetService datasetService;

    @Autowired(required = false)
    @Nullable
    private DesktopOwlApiMutationService desktopMutation;

    public MergeLiveSource(StorageManager storageManager, SparqlDatasetService datasetService) {
        this.storageManager = storageManager;
        this.datasetService = datasetService;
    }

    public Optional<Path> export(String projectId) throws IOException {
        if (desktopMutation != null && desktopMutation.isAuthoritative(projectId)) {
            Path temp = tempFile(projectId);
            if (desktopMutation.exportLiveModel(projectId, temp)) {
                return Optional.of(temp);
            }
            Files.deleteIfExists(temp);
            return Optional.empty();
        }
        if (Files.exists(storageManager.projectDir(projectId).resolve("ontology.dirty"))) {
            Path temp = tempFile(projectId);
            try (OutputStream out = Files.newOutputStream(temp)) {
                datasetService.exportDatasetToStream(projectId, RDFFormat.RDFXML, out);
            }
            return Optional.of(temp);
        }
        return Optional.empty();
    }

    public Path exportDraft(String projectId, String userId) throws IOException {
        Path temp = tempFile(projectId);
        Files.writeString(temp, datasetService.exportNamedGraph(projectId,
                datasetService.getDraftGraphUri(projectId, userId), RDFFormat.RDFXML));
        return temp;
    }

    public static OWLDocumentFormat formatFor(Path target) {
        String name = target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".ttl") || name.endsWith(".turtle")) {
            return new TurtleDocumentFormat();
        }
        if (name.endsWith(".owx")) {
            return new OWLXMLDocumentFormat();
        }
        if (name.endsWith(".ofn")) {
            return new FunctionalSyntaxDocumentFormat();
        }
        if (name.endsWith(".omn")) {
            return new ManchesterSyntaxDocumentFormat();
        }
        return new RDFXMLDocumentFormat();
    }

    private Path tempFile(String projectId) throws IOException {
        Path dir = storageManager.projectDir(projectId);
        Files.createDirectories(dir);
        return dir.resolve("merge-live-" + UUID.randomUUID() + ".owl");
    }
}
