package self.research.ontology.owlEditor.service.delta;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;

@EnabledIfEnvironmentVariable(named = "FUSEKI_URL", matches = ".+")
class FusekiDeltaRoundTripTest extends DeltaRoundTripTest {

    private static String base() {
        String url = System.getenv("FUSEKI_URL");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    protected Repository createRepository() {
        SPARQLRepository created = new SPARQLRepository(base() + "/sparql", base() + "/update");
        created.init();
        try (RepositoryConnection connection = created.getConnection()) {
            connection.prepareUpdate("DROP SILENT GRAPH <" + G + ">").execute();
        }
        return created;
    }

    @Override
    protected void load(String ttl) throws Exception {
        String graph = URLEncoder.encode(G, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/data?graph=" + graph))
                .header("Content-Type", "text/turtle")
                .POST(HttpRequest.BodyPublishers.ofString(ttl, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Could not load the fixture: " + response.statusCode() + " " + response.body());
        }
    }
}
