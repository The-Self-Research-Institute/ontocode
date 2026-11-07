package self.research.ontology.owlEditor.util;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SafeUrlFetch {

    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    private record Fetched(Path file, URI redirect) {
    }

    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final String INTERNAL_MESSAGE = "The URL points to an internal address";

    private SafeUrlFetch() {
    }

    public static URL requirePublicHttpUrl(String uri) throws IOException {
        URI parsed = requireHttpUri(uri);
        vet(InetAddress.getAllByName(parsed.getHost()));
        return parsed.toURL();
    }

    private static URI requireHttpUri(String uri) throws IOException {
        URI parsed;
        try {
            parsed = URI.create(uri.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("Not a valid URL");
        }
        String scheme = parsed.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IOException("Only http and https URLs can be fetched");
        }
        String host = parsed.getHost();
        if (host == null || host.isBlank() || parsed.getUserInfo() != null) {
            throw new IOException("The URL has no usable host");
        }
        return parsed;
    }

    private static InetAddress[] vet(InetAddress[] addresses) throws IOException {
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException("The host could not be found");
        }
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                throw new IOException(INTERNAL_MESSAGE);
            }
        }
        return addresses;
    }

    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            int third = bytes[2] & 0xff;
            return first == 0
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 192 && second == 0 && (third == 0 || third == 2))
                    || (first == 192 && second == 88 && third == 99)
                    || (first == 198 && (second == 18 || second == 19))
                    || (first == 198 && second == 51 && third == 100)
                    || (first == 203 && second == 0 && third == 113)
                    || first >= 240;
        }
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            int third = bytes[2] & 0xff;
            int fourth = bytes[3] & 0xff;
            return (first & 0xfe) == 0xfc
                    || (first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8)
                    || (first == 0x20 && second == 0x01 && third == 0 && fourth == 0)
                    || (first == 0x20 && second == 0x02)
                    || (first == 0x01 && second == 0 && allZero(bytes, 2, 8))
                    || (first == 0x00 && second == 0x64 && third == 0xff && fourth == 0x9b);
        }
        return false;
    }

    private static boolean allZero(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    public static Path downloadToTemp(String uri, long maxBytes, boolean allowInternal) throws IOException {
        return downloadToTemp(uri, maxBytes, allowInternal, InetAddress::getAllByName);
    }

    static Path downloadToTemp(String uri, long maxBytes, boolean allowInternal, Resolver resolver)
            throws IOException {
        DnsResolver dns = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                try {
                    InetAddress[] addresses = resolver.resolve(host);
                    return allowInternal ? addresses : vet(addresses);
                } catch (UnknownHostException e) {
                    throw e;
                } catch (IOException e) {
                    throw new UnknownHostException(e.getMessage());
                }
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        PoolingHttpClientConnectionManager manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dns)
                .setMaxConnTotal(1)
                .setMaxConnPerRoute(1)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT_MS))
                        .setSocketTimeout(Timeout.ofMilliseconds(READ_TIMEOUT_MS))
                        .build())
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setRedirectsEnabled(false)
                .setResponseTimeout(Timeout.ofMilliseconds(READ_TIMEOUT_MS))
                .build();
        try (CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(manager)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .build()) {
            URI current = requireHttpUri(uri);
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                Fetched fetched = fetchOnce(client, current, maxBytes);
                if (fetched.file() != null) {
                    return fetched.file();
                }
                current = requireHttpUri(fetched.redirect().toString());
            }
        }
        throw new IOException("Too many redirects");
    }

    private static Fetched fetchOnce(CloseableHttpClient client, URI target, long maxBytes) throws IOException {
        HttpGet get = new HttpGet(target);
        get.setHeader("Accept", "application/rdf+xml, text/turtle, application/n-triples, */*");
        return client.execute(get, response -> {
            int code = response.getCode();
            if (code >= 300 && code < 400) {
                Header location = response.getFirstHeader("Location");
                EntityUtils.consumeQuietly(response.getEntity());
                if (location == null || location.getValue().isBlank()) {
                    throw new IOException("Redirect without a location");
                }
                try {
                    return new Fetched(null, target.resolve(location.getValue().trim()));
                } catch (IllegalArgumentException e) {
                    throw new IOException("Bad redirect", e);
                }
            }
            if (code < 200 || code >= 300) {
                EntityUtils.consumeQuietly(response.getEntity());
                throw new IOException("HTTP " + code);
            }
            HttpEntity entity = response.getEntity();
            if (entity == null) {
                throw new IOException("The server sent no content");
            }
            Path tmp = Files.createTempFile("owl-import-", suffixOf(target));
            try (InputStream in = entity.getContent()) {
                copyBounded(in, tmp, maxBytes);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
            return new Fetched(tmp, null);
        });
    }

    private static String suffixOf(URI url) {
        String path = url.getPath() == null ? "" : url.getPath();
        String leaf = path.substring(path.lastIndexOf('/') + 1);
        return leaf.contains(".") ? leaf.substring(leaf.lastIndexOf('.')) : ".owl";
    }

    private static void copyBounded(InputStream in, Path target, long maxBytes) throws IOException {
        try (var out = Files.newOutputStream(target, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("The download is larger than " + maxBytes + " bytes");
                }
                out.write(buffer, 0, read);
            }
        }
    }
}
