package self.research.ontology.owlEditor.service.delta;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "FUSEKI_URL", matches = ".+")
class FusekiScaleTest {

    private static final String G = "urn:test:scale";
    private static final int CLASSES = 16_000;

    private static String base() {
        String url = System.getenv("FUSEKI_URL");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String ontology() {
        StringBuilder ttl = new StringBuilder("@prefix : <http://ex/> .\n@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n");
        for (int i = 0; i < CLASSES; i++) {
            ttl.append(":C").append(i).append(" a owl:Class ; rdfs:label \"Class ").append(i).append("\" ; rdfs:subClassOf ")
                    .append(i == 0 ? "owl:Thing" : ":C" + (i / 2))
                    .append(" , [ a owl:Restriction ; owl:onProperty :p ; owl:someValuesFrom :C").append((i * 7) % CLASSES)
                    .append(" ] .\n");
        }
        return ttl.toString();
    }

    @Test
    void capturingAndUndoingAClassDeleteOnABigGraphStaysPractical() throws Exception {
        SPARQLRepository sparql = new SPARQLRepository(base() + "/sparql", base() + "/update");
        sparql.init();
        Repository repo = sparql;
        try (RepositoryConnection c = repo.getConnection()) {
            c.prepareUpdate("DROP SILENT GRAPH <" + G + ">").execute();
        }
        HttpRequest load = HttpRequest.newBuilder(URI.create(base() + "/data?graph=" + URLEncoder.encode(G, StandardCharsets.UTF_8)))
                .header("Content-Type", "text/turtle").POST(HttpRequest.BodyPublishers.ofString(ontology())).build();
        HttpResponse<String> loaded = HttpClient.newHttpClient().send(load, HttpResponse.BodyHandlers.ofString());
        assertTrue(loaded.statusCode() / 100 == 2, loaded.body());

        long t0 = System.nanoTime();
        Optional<DeltaCapture> capture = DeltaCapture.beginWholeGraph(repo, G);
        long beginMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(capture.isPresent(), "graph larger than the capture limit");

        try (RepositoryConnection c = repo.getConnection()) {
            c.prepareUpdate("WITH <" + G + "> DELETE { ?s ?p <http://ex/C5> } WHERE { ?s ?p <http://ex/C5> } ; WITH <" + G
                    + "> DELETE { <http://ex/C5> ?p ?o } WHERE { <http://ex/C5> ?p ?o }").execute();
        }
        long t1 = System.nanoTime();
        Optional<ExactDelta> delta = capture.get().finish();
        long finishMs = (System.nanoTime() - t1) / 1_000_000;
        assertTrue(delta.isPresent());

        System.out.println("[SCALE] classes=" + CLASSES + " begin=" + beginMs + "ms finish=" + finishMs + "ms deltaSize="
                + (delta.get().deleted().size() + delta.get().inserted().size()));
        assertTrue(beginMs < 60_000 && finishMs < 60_000, "capture took too long");
    }
}
