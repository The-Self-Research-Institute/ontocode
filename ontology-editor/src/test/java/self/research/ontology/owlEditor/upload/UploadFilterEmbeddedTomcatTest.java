package self.research.ontology.owlEditor.upload;

import jakarta.servlet.FilterChain;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadFilterEmbeddedTomcatTest {

    @TempDir
    Path tmp;

    private Tomcat tomcat;
    private int port;
    private static final AtomicBoolean WRAPPER_STREAM_USED_BY_GET_PARTS = new AtomicBoolean();

    static class ProbeFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws jakarta.servlet.ServletException, IOException {
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override
                public jakarta.servlet.ServletInputStream getInputStream() throws IOException {
                    WRAPPER_STREAM_USED_BY_GET_PARTS.set(true);
                    return super.getInputStream();
                }
            }, response);
        }
    }

    static class EchoServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            long total = 0;
            try {
                if (req.getContentType() != null && req.getContentType().startsWith("multipart/")) {
                    for (Part part : req.getParts()) {
                        total += part.getSize();
                    }
                } else {
                    total = req.getInputStream().readAllBytes().length;
                }
            } catch (jakarta.servlet.ServletException e) {
                throw new IOException(e);
            }
            resp.setContentType("text/plain");
            resp.getWriter().write("ok:" + total);
        }
    }

    private void start(MockEnvironment env, boolean probeOnly) throws Exception {
        tomcat = new Tomcat();
        tomcat.setBaseDir(tmp.toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context ctx = tomcat.addContext("", tmp.toString());
        org.apache.catalina.Wrapper wrapper = Tomcat.addServlet(ctx, "echo", new EchoServlet());
        wrapper.setMultipartConfigElement(new MultipartConfigElement(tmp.toString(), -1, -1, 1024));
        ctx.addServletMappingDecoded("/*", "echo");
        FilterDef def = new FilterDef();
        def.setFilterName("limit");
        def.setFilter(probeOnly ? new ProbeFilter()
                : new UploadBytesLimitFilter(new UploadSettings(env), new SemaphoreUploadAdmission(3, 10)));
        ctx.addFilterDef(def);
        FilterMap map = new FilterMap();
        map.setFilterName("limit");
        map.addURLPattern("/*");
        ctx.addFilterMap(map);
        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    @BeforeEach
    void reset() {
        WRAPPER_STREAM_USED_BY_GET_PARTS.set(false);
    }

    @AfterEach
    void stop() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    private String[] post(String path, String contentType, byte[] body, boolean chunked) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create("http://localhost:" + port + path).toURL().openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", contentType);
        if (chunked) {
            c.setChunkedStreamingMode(256);
        } else {
            c.setFixedLengthStreamingMode(body.length);
        }
        try (OutputStream out = c.getOutputStream()) {
            out.write(body);
        } catch (IOException ignored) {
        }
        int status = c.getResponseCode();
        java.io.InputStream stream = status >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        return new String[] {String.valueOf(status), text, String.valueOf(c.getRequestProperty("Transfer-Encoding"))};
    }

    private static byte[] multipart(String boundary, int fileBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.xlsx\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        out.writeBytes(head);
        out.writeBytes(new byte[fileBytes]);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private MockEnvironment small() {
        return new MockEnvironment().withProperty("ontocode.capacity.upload.max-bytes.spreadsheet", "2000");
    }

    @Test
    void chunkedBodyWithoutContentLengthIsRejectedOnARealConnector() throws Exception {
        start(small(), false);
        String[] r = post("/api/ontology/spreadsheet-import/p/parse", "application/octet-stream", new byte[20_000], true);
        assertEquals("413", r[0]);
        assertTrue(r[1].contains("limitBytes"), r[1]);
    }

    @Test
    void chunkedBodyUnderTheLimitPasses() throws Exception {
        start(small(), false);
        String[] r = post("/api/ontology/spreadsheet-import/p/parse", "application/octet-stream", new byte[1500], true);
        assertEquals("200", r[0]);
        assertEquals("ok:1500", r[1]);
    }

    @Test
    void declaredOversizeIsRejectedBeforeReading() throws Exception {
        start(small(), false);
        String[] r = post("/api/ontology/spreadsheet-import/p/parse", "application/octet-stream", new byte[20_000], false);
        assertEquals("413", r[0]);
    }

    @Test
    void tomcatMultipartParsingDoesNotReadThroughTheWrappedStream() throws Exception {
        start(small(), true);
        String boundary = "XXBOUNDARYXX";
        String[] r = post("/x", "multipart/form-data; boundary=" + boundary, multipart(boundary, 5000), true);
        assertEquals("200", r[0]);
        assertFalse(WRAPPER_STREAM_USED_BY_GET_PARTS.get(),
                "multipart parsing read the wrapper stream, so the wrapper does cover it");
    }

    @Test
    void chunkedMultipartOverTheLimitIsStillRejectedAfterParsing() throws Exception {
        start(small(), false);
        String boundary = "XXBOUNDARYXX";
        String[] r = post("/api/ontology/spreadsheet-import/p/parse", "multipart/form-data; boundary=" + boundary,
                multipart(boundary, 5000), true);
        assertEquals("413", r[0]);
    }

    @Test
    void chunkedMultipartUnderTheLimitPasses() throws Exception {
        start(small(), false);
        String boundary = "XXBOUNDARYXX";
        String[] r = post("/api/ontology/spreadsheet-import/p/parse", "multipart/form-data; boundary=" + boundary,
                multipart(boundary, 1000), true);
        assertEquals("200", r[0]);
        assertEquals("ok:1000", r[1]);
    }
}
