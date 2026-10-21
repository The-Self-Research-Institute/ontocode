package self.research.ontology.owlEditor.service;

import com.sun.net.httpserver.HttpServer;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class DraftAxiomCounterImportsTest {

    private HttpServer importHost;
    private final AtomicInteger importHostHits = new AtomicInteger();
    private SparqlDatasetService datasetService;

    @BeforeEach
    void setUp() throws Exception {
        importHost = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        importHost.createContext("/", exchange -> {
            importHostHits.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        importHost.start();
        String draft = "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<http://ex.org/o> a owl:Ontology ; owl:imports <http://127.0.0.1:" + importHost.getAddress().getPort()
                + "/internal> .\n"
                + "<http://ex.org/A> a owl:Class .\n";
        datasetService = mock(SparqlDatasetService.class);
        doAnswer(call -> {
            OutputStream out = call.getArgument(3, OutputStream.class);
            out.write(draft.getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(datasetService).exportDatasetToStream(anyString(), anyString(), any(RDFFormat.class), any(OutputStream.class));
    }

    @AfterEach
    void tearDown() {
        importHost.stop(0);
    }

    @Test
    void aDraftThatImportsAnotherOntologyIsCountedWithoutContactingTheImportAddress() {
        OntologyMetrics.OwlApiAxiomCounts counts = new DraftAxiomCounter(datasetService).count("proj-1", "u-1");

        assertNotNull(counts, "the count must not fail because an import cannot be fetched");
        assertTrue(counts.declarations() >= 1);
        assertEquals(0, importHostHits.get(), "the server must not call the address named in owl:imports");
    }
}
