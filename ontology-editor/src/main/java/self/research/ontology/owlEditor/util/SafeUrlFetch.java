package self.research.ontology.owlEditor.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SafeUrlFetch {

    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private SafeUrlFetch() {
    }

    public static URL requirePublicHttpUrl(String uri) throws IOException {
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
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                throw new IOException("The URL points to an internal address");
            }
        }
        return parsed.toURL();
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
            return first == 0 || (first == 100 && second >= 64 && second <= 127) || first >= 240;
        }
        if (address instanceof Inet6Address) {
            return (bytes[0] & 0xfe) == 0xfc;
        }
        return false;
    }

    public static Path downloadToTemp(String uri, long maxBytes, boolean allowInternal) throws IOException {
        URL url = allowInternal ? URI.create(uri.trim()).toURL() : requirePublicHttpUrl(uri);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/rdf+xml, text/turtle, application/n-triples, */*");
            try {
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException("Redirect without a location");
                    }
                    String next = url.toURI().resolve(location).toString();
                    url = allowInternal ? URI.create(next).toURL() : requirePublicHttpUrl(next);
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new IOException("HTTP " + code);
                }
                Path tmp = Files.createTempFile("owl-import-", suffixOf(url));
                try (InputStream in = conn.getInputStream()) {
                    copyBounded(in, tmp, maxBytes);
                } catch (IOException | RuntimeException e) {
                    Files.deleteIfExists(tmp);
                    throw e;
                }
                return tmp;
            } catch (java.net.URISyntaxException e) {
                throw new IOException("Bad redirect", e);
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private static String suffixOf(URL url) {
        String leaf = url.getPath().substring(url.getPath().lastIndexOf('/') + 1);
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
