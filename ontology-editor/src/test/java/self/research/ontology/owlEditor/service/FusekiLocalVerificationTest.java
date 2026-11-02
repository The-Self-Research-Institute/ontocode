package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fuseki-local")
@EnabledIfSystemProperty(named = "fuseki.jar", matches = ".+")
class FusekiLocalVerificationTest {

    static Process server;
    static int port;
    static Path work;
    static Path tmp;
    static HttpClient http = HttpClient.newHttpClient();

    static String config() {
        return """
                PREFIX fuseki: <http://jena.apache.org/fuseki#>
                PREFIX ja: <http://jena.hpl.hp.com/2005/11/Assembler#>
                PREFIX : <#>

                :service a fuseki:Service ;
                  fuseki:name "ds" ;
                  fuseki:endpoint [ fuseki:operation fuseki:gsp-rw ; fuseki:name "data" ] ;
                  fuseki:endpoint [ fuseki:operation fuseki:query ; fuseki:name "plain" ] ;
                  fuseki:endpoint [ fuseki:operation fuseki:query ; fuseki:name "timeout" ;
                      ja:context [ ja:cxtName "arq:queryTimeout" ; ja:cxtValue "1000" ] ] ;
                  fuseki:endpoint [ fuseki:operation fuseki:query ; fuseki:name "spill" ;
                      ja:context [ ja:cxtName "arq:spillToDiskThreshold" ; ja:cxtValue "1000" ] ] ;
                  fuseki:dataset :dataset .

                :dataset a ja:MemoryDataset .
                """;
    }

    @BeforeAll
    static void start() throws Exception {
        String jar = System.getProperty("fuseki.jar");
        work = Files.createTempDirectory("fuseki-verify-work");
        tmp = Files.createTempDirectory("fuseki-verify-tmp");
        Files.writeString(work.resolve("config.ttl"), config());
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        ProcessBuilder pb = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.io.tmpdir=" + tmp,
                "-jar", Path.of(jar).toAbsolutePath().toString(),
                "--config=" + work.resolve("config.ttl"), "--port=" + port);
        pb.directory(work.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(work.resolve("server.log").toFile());
        server = pb.start();
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/$/ping"))
                        .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                Thread.sleep(300);
            }
        }
        throw new IllegalStateException("Fuseki did not start: " + Files.readString(work.resolve("server.log")));
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.destroyForcibly();
        }
    }

    static void load(String nt) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ds/data?default"))
                .header("Content-Type", "application/n-triples")
                .POST(HttpRequest.BodyPublishers.ofString(nt)).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(r.statusCode() / 100 == 2, "load failed: " + r.statusCode() + " " + r.body());
    }

    record Outcome(int status, long millis, String bodyStart, boolean clientTimedOut, Set<String> newTmpFiles) {}

    static Outcome query(String endpoint, String sparql, int clientSeconds) throws Exception {
        Set<String> before = tmpFiles();
        Set<String> seen = new TreeSet<>();
        AtomicBoolean running = new AtomicBoolean(true);
        Thread poll = new Thread(() -> {
            while (running.get()) {
                Set<String> now = tmpFiles();
                now.removeAll(before);
                seen.addAll(now);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        poll.start();
        long t0 = System.nanoTime();
        int status = -1;
        String body = "";
        boolean timedOut = false;
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ds/" + endpoint
                            + "?query=" + URLEncoder.encode(sparql, StandardCharsets.UTF_8)))
                    .header("Accept", "application/sparql-results+json")
                    .timeout(Duration.ofSeconds(clientSeconds)).build(), HttpResponse.BodyHandlers.ofString());
            status = r.statusCode();
            body = r.body().substring(0, Math.min(200, r.body().length())).replace("\n", " ");
        } catch (java.net.http.HttpTimeoutException e) {
            timedOut = true;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        running.set(false);
        poll.join();
        return new Outcome(status, ms, body, timedOut, new TreeSet<>(seen));
    }

    static Set<String> tmpFiles() {
        try (Stream<Path> s = Files.walk(tmp)) {
            Set<String> out = new TreeSet<>();
            s.filter(Files::isRegularFile).forEach(p -> out.add(tmp.relativize(p).toString()));
            return out;
        } catch (IOException e) {
            return new TreeSet<>();
        }
    }

    @Test
    void queryTimeoutAndSortSpillBehaviour() throws Exception {
        StringBuilder nt = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            nt.append("<http://e/t").append(i).append("> <http://e/q> \"").append((i * 7919L) % 100003).append("\" .\n");
        }
        load(nt.toString());
        String sort = "SELECT ?s ?o WHERE { ?s <http://e/q> ?o } ORDER BY ?o";
        Outcome noSpill = query("plain", sort, 60);
        Outcome spill = query("spill", sort, 60);
        System.out.println("EVIDENCE sort control (no threshold): status=" + noSpill.status + " ms=" + noSpill.millis
                + " newTmpFiles=" + noSpill.newTmpFiles.size());
        System.out.println("EVIDENCE sort with arq:spillToDiskThreshold=1000: status=" + spill.status + " ms=" + spill.millis
                + " newTmpFiles=" + spill.newTmpFiles.size() + " names=" + spill.newTmpFiles.stream().limit(5).toList());

        String cross = "SELECT (COUNT(*) AS ?cnt) WHERE { ?a ?p ?b . ?c ?q ?d . ?e ?r ?f }";
        Outcome timeout = query("timeout", cross, 30);
        System.out.println("EVIDENCE timeout configured arq:queryTimeout=1000: clientTimedOut=" + timeout.clientTimedOut
                + " status=" + timeout.status + " ms=" + timeout.millis + " body=" + timeout.bodyStart);
        Outcome plain = query("plain", cross, 8);
        System.out.println("EVIDENCE timeout control (no arq:queryTimeout): clientTimedOut=" + plain.clientTimedOut
                + " status=" + plain.status + " ms=" + plain.millis);

        assertTrue(plain.clientTimedOut, "control query unexpectedly finished: " + plain);
        assertTrue(!timeout.clientTimedOut && timeout.millis < 15_000, "arq:queryTimeout did not stop the query: " + timeout);
        assertTrue(noSpill.newTmpFiles.isEmpty(), "control sort created temp files: " + noSpill);
        assertTrue(!spill.newTmpFiles.isEmpty(), "spillToDiskThreshold produced no temp files: " + spill);
    }
}
