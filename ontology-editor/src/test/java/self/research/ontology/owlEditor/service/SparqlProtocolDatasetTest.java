package self.research.ontology.owlEditor.service;

import com.sun.net.httpserver.HttpServer;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparqlProtocolDatasetTest {

    static final String A = SparqlGraphUris.mainProjectGraph("projA");
    static final String A_PART = A + "/ns/x";

    HttpServer server;
    SPARQLRepository repo;
    final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/query", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestURI().getRawQuery() + "&" + body);
            String q = URLDecoder.decode(exchange.getRequestURI().getRawQuery() + "&" + body, StandardCharsets.UTF_8);
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            byte[] out;
            String type;
            if (accept != null && accept.contains("sparql-results+json") && q.contains("ASK")) {
                out = "{\"head\":{},\"boolean\":false}".getBytes(StandardCharsets.UTF_8);
                type = "application/sparql-results+json";
            } else if (accept != null && accept.contains("sparql-results+json")) {
                out = "{\"head\":{\"vars\":[\"s\"]},\"results\":{\"bindings\":[]}}".getBytes(StandardCharsets.UTF_8);
                type = "application/sparql-results+json";
            } else {
                out = "".getBytes(StandardCharsets.UTF_8);
                type = "text/turtle";
            }
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(200, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        server.start();
        repo = new SPARQLRepository("http://127.0.0.1:" + server.getAddress().getPort() + "/query");
        repo.init();
    }

    @AfterEach
    void tearDown() {
        repo.shutDown();
        server.stop(0);
    }

    private Set<String> params(String raw, String name) {
        Set<String> out = new LinkedHashSet<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                out.add(URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private void assertScoped(String request) {
        assertEquals(Set.of(A, A_PART), params(request, "default-graph-uri"), request);
        assertEquals(Set.of(A, A_PART), params(request, "named-graph-uri"), request);
        assertTrue(!request.contains("FROM"), request);
    }

    @Test
    void selectAskConstructAndDescribeSendTheServerChosenGraphsAsProtocolParameters() {
        SparqlQueryScope scope = SparqlQueryScope.publicScope("projA", A, List.of(A_PART));
        List<String> queries = new ArrayList<>(List.of(
                "SELECT ?s WHERE { ?s ?p ?o }",
                "ASK { GRAPH ?g { ?s ?p ?o } }",
                "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }",
                "DESCRIBE <http://e/s1>"));
        for (String q : queries) {
            SparqlQueryGuard.Checked checked = SparqlQueryGuard.check(q, new LinkedHashSet<>(scope.graphs()));
            try (RepositoryConnection c = repo.getConnection()) {
                SparqlUserQueryRunner.run(c, checked, scope.graphs(), 100);
            }
        }
        assertTrue(requests.size() >= 4, requests.toString());
        requests.forEach(this::assertScoped);
    }

    @Test
    void userCannotAddGraphParametersThroughTheQueryText() {
        SparqlQueryScope scope = SparqlQueryScope.publicScope("projA", A, List.of(A_PART));
        try {
            SparqlQueryGuard.check("SELECT * FROM <http://ontocode.org/project/projB> WHERE { ?s ?p ?o }",
                    new LinkedHashSet<>(scope.graphs()));
        } catch (SparqlQueryRejectedException expected) {
            assertTrue(requests.isEmpty());
            return;
        }
        throw new AssertionError("FROM must be rejected");
    }
}
