package self.research.ontology.owlEditor.util;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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

    @ParameterizedTest
    @ValueSource(strings = {"192.0.0.8", "192.0.2.1", "198.18.0.1", "198.19.255.254", "198.51.100.7", "203.0.113.9",
            "192.88.99.1", "240.0.0.1", "255.255.255.255", "2001:db8::1", "2002::1", "64:ff9b::1", "2001::1"})
    void reservedRangesAreInternal(String literal) throws Exception {
        assertTrue(SafeUrlFetch.isInternal(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "8.8.8.8", "198.20.0.1", "2606:4700::1111"})
    void ordinaryPublicAddressesAreNotInternal(String literal) throws Exception {
        assertTrue(!SafeUrlFetch.isInternal(InetAddress.getByName(literal)), literal);
    }

    @Test
    void aHostThatResolvesToAnInternalAddressIsRefusedWithoutConnecting() throws Exception {
        List<String> asked = new ArrayList<>();
        SafeUrlFetch.Resolver rebinding = host -> {
            asked.add(host);
            return new InetAddress[]{InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1")};
        };

        assertThrows(IOException.class,
                () -> SafeUrlFetch.downloadToTemp("http://rebind.example/x.owl", 1024, false, rebinding));
        assertEquals(List.of("rebind.example"), asked);
    }

    @Test
    void theConnectionGoesToTheResolvedAddressWithTheOriginalHostHeader() throws Exception {
        AtomicReference<String> hostHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
            byte[] body = "<rdf/>".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        List<String> asked = new ArrayList<>();
        SafeUrlFetch.Resolver fake = host -> {
            asked.add(host);
            return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
        };
        try {
            int port = server.getAddress().getPort();
            Path file = SafeUrlFetch.downloadToTemp("http://files.example:" + port + "/a.owl", 1024, true, fake);
            assertEquals("<rdf/>", Files.readString(file));
            Files.deleteIfExists(file);
            assertEquals("files.example:" + port, hostHeader.get());
            assertEquals(List.of("files.example"), asked);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void everyRedirectHopIsResolvedAgain() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://second.example:" + port + "/final");
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
        List<String> asked = new ArrayList<>();
        SafeUrlFetch.Resolver fake = host -> {
            asked.add(host);
            return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
        };
        try {
            Path file = SafeUrlFetch.downloadToTemp("http://first.example:" + port + "/start", 1024, true, fake);
            Files.deleteIfExists(file);
            assertEquals(List.of("first.example", "second.example"), asked);
        } finally {
            server.stop(0);
        }
    }
}
