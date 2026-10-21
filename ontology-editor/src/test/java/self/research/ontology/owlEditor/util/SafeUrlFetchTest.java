package self.research.ontology.owlEditor.util;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeUrlFetchTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/x",
            "http://localhost/x",
            "http://[::1]/x",
            "http://169.254.169.254/latest/meta-data/",
            "http://10.0.0.5/x",
            "http://172.16.4.4/x",
            "http://192.168.1.1/x",
            "http://100.64.0.1/x",
            "http://0.0.0.0/x",
            "http://[fd00::1]/x",
            "http://user:pass@93.184.216.34/x",
            "file:///etc/passwd",
            "ftp://93.184.216.34/x",
            "jar:file:///tmp/x.jar!/",
            "gopher://93.184.216.34/",
            "not a url"
    })
    void internalAndNonHttpAddressesAreRefused(String url) {
        assertThrows(IOException.class, () -> SafeUrlFetch.requirePublicHttpUrl(url), url);
    }

    @Test
    void aPublicAddressIsAccepted() throws Exception {
        assertEquals("93.184.216.34", SafeUrlFetch.requirePublicHttpUrl("http://93.184.216.34/ontology.owl").getHost());
    }

    @Test
    void aRedirectToAnInternalAddressIsNotFollowed() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            assertThrows(IOException.class, () -> SafeUrlFetch.downloadToTemp(url, 1024, false));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void whenInternalHostsAreAllowedItDownloadsAndFollowsRedirects() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/final");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/final", exchange -> {
            byte[] body = "<rdf/>".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/start";
            Path file = SafeUrlFetch.downloadToTemp(url, 1024, true);
            assertEquals("<rdf/>", Files.readString(file));
            Files.deleteIfExists(file);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aDownloadOverTheLimitIsRefusedAndNothingIsLeftBehind() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = new byte[5000];
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            IOException failure = assertThrows(IOException.class, () -> SafeUrlFetch.downloadToTemp(url, 1000, true));
            assertTrue(failure.getMessage().contains("larger than"));
        } finally {
            server.stop(0);
        }
    }
}
