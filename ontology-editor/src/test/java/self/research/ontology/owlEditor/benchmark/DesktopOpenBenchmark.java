package self.research.ontology.owlEditor.benchmark;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.nio.file.Files;
import java.nio.file.Path;

public final class DesktopOpenBenchmark {

    private DesktopOpenBenchmark() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: DesktopOpenBenchmark <path-to.owl>");
            System.exit(1);
        }
        Path file = Path.of(args[0]);
        if (!Files.exists(file)) {
            System.err.println("File not found: " + file);
            System.exit(2);
        }

        long start = System.currentTimeMillis();
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.setOntologyLoaderConfiguration(
                new OWLOntologyLoaderConfiguration()
                        .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                        .setLoadAnnotationAxioms(true));
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(file.toFile());
        long parseMs = System.currentTimeMillis() - start;
        long classes = ontology.classesInSignature().count();
        System.out.println("PARSE_MS=" + parseMs);
        System.out.println("CLASSES=" + classes);
        System.out.println("FILE=" + file.getFileName());
        System.out.println("BYTES=" + Files.size(file));
        manager.removeOntology(ontology);
    }
}
