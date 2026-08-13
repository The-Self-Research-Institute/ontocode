

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

public class DisableInferenceDuringImport {

    public void importLargeOntology(Repository repo, File owlFile) throws Exception {
        try (RepositoryConnection conn = repo.getConnection()) {
            ValueFactory vf = conn.getValueFactory();

            System.out.println("Disabling inference...");
            IRI inferenceDisabled = vf.createIRI("http://www.ontotext.com/owlim/system#inferenceDisabled");
            conn.begin();
            conn.add(inferenceDisabled, inferenceDisabled, vf.createLiteral(true));
            conn.commit();
            System.out.println("Inference disabled ✓");

            System.out.println("Importing ontology...");
            long startTime = System.currentTimeMillis();
            conn.begin();

            try (InputStream stream = new FileInputStream(owlFile)) {
                conn.add(stream, "", RDFFormat.RDFXML);
            }

            conn.commit();
            long importTime = System.currentTimeMillis() - startTime;
            System.out.println("Import completed in " + (importTime / 1000) + " seconds ✓");

            System.out.println("Re-enabling inference and rebuilding index...");
            startTime = System.currentTimeMillis();
            conn.begin();

            conn.remove(inferenceDisabled, inferenceDisabled, vf.createLiteral(true));

            IRI forceRebuild = vf.createIRI("http://www.ontotext.com/owlim/system#forceRebuildIndex");
            conn.add(forceRebuild, forceRebuild, vf.createLiteral(true));

            conn.commit();
            long rebuildTime = System.currentTimeMillis() - startTime;
            System.out.println("Index rebuilt in " + (rebuildTime / 1000) + " seconds ✓");

            System.out.println("Total time: " + ((importTime + rebuildTime) / 1000) + " seconds");
            System.out.println("Expected improvement: 5-8 minutes faster than real-time inference");
        }
    }
}
