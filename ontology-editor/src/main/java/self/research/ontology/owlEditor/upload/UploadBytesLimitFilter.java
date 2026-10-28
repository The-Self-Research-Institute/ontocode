package self.research.ontology.owlEditor.upload;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class UploadBytesLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UploadBytesLimitFilter.class);

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final List<UploadSettings.Rule> rules;
    private final UploadAdmission admission;

    public UploadBytesLimitFilter(UploadSettings settings, UploadAdmission admission) {
        this.rules = settings.rules();
        this.admission = admission;
    }

    UploadSettings.Rule match(String path) {
        for (UploadSettings.Rule rule : rules) {
            for (String pattern : rule.patterns()) {
                if (matcher.match(pattern, path)) {
                    return rule;
                }
            }
        }
        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"POST".equals(method) && !"PUT".equals(method) && !"PATCH".equals(method)) {
            return true;
        }
        return match(pathOf(request)) == null;
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        return uri;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UploadSettings.Rule rule = match(pathOf(request));
        long limit = rule.limitBytes();
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            log.warn("[Upload] Rejected {} {}: Content-Length {} exceeds {} limit of {} bytes",
                    request.getMethod(), request.getRequestURI(), declared, rule.name(), limit);
            tooLarge(response, limit);
            return;
        }

        UploadAdmission.Lease lease;
        try {
            lease = admission.tryAdmit();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            busy(response);
            return;
        }
        if (lease == null) {
            log.warn("[Upload] Rejected {} {}: too many uploads in progress", request.getMethod(), request.getRequestURI());
            busy(response);
            return;
        }

        try {
            chain.doFilter(new LimitedRequest(request, limit), response);
        } catch (Exception e) {
            UploadLimitExceededException exceeded = UploadExceptionAdvice.find(e);
            if (exceeded == null) {
                throw e;
            }
            log.warn("[Upload] Aborted {} {}: body exceeded {} limit of {} bytes",
                    request.getMethod(), request.getRequestURI(), rule.name(), limit);
            if (!response.isCommitted()) {
                response.reset();
                tooLarge(response, limit);
            }
        } finally {
            lease.close();
        }
    }

    private static void tooLarge(HttpServletResponse response, long limit) throws IOException {
        String message = "The upload is larger than the allowed " + (limit / (1024 * 1024)) + " MB";
        write(response, 413, "{\"success\":false,\"error\":\"" + message + "\",\"limitBytes\":" + limit + "}");
    }

    private void busy(HttpServletResponse response) throws IOException {
        int retry = admission.retryAfterSeconds();
        response.setHeader("Retry-After", String.valueOf(retry));
        write(response, 429, "{\"success\":false,\"busy\":true,\"reason\":\"too_many_uploads\",\"retryAfterSeconds\":"
                + retry + ",\"message\":\"The server is busy with other uploads. Please try again in a few seconds.\"}");
    }

    private static void write(HttpServletResponse response, int status, String json) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();
    }

    static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long limit;
        private LimitedInputStream limited;
        private ServletInputStream servletStream;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (servletStream == null) {
                ServletInputStream raw = super.getInputStream();
                limited = new LimitedInputStream(raw, limit);
                servletStream = new ServletInputStream() {
                    @Override
                    public boolean isFinished() {
                        return raw.isFinished();
                    }

                    @Override
                    public boolean isReady() {
                        return raw.isReady();
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        raw.setReadListener(listener);
                    }

                    @Override
                    public int read() throws IOException {
                        return limited.read();
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        return limited.read(b, off, len);
                    }
                };
            }
            return servletStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String enc = getCharacterEncoding();
            Charset cs = enc != null ? Charset.forName(enc) : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(getInputStream(), cs));
        }

        @Override
        public Collection<Part> getParts() throws IOException, ServletException {
            Collection<Part> parts = super.getParts();
            enforce(parts);
            return parts;
        }

        @Override
        public Part getPart(String name) throws IOException, ServletException {
            enforce(super.getParts());
            return super.getPart(name);
        }

        private void enforce(Collection<Part> parts) throws UploadLimitExceededException {
            long total = 0;
            for (Part part : parts) {
                total += part.getSize();
            }
            if (total > limit) {
                for (Part part : parts) {
                    try {
                        part.delete();
                    } catch (IOException ignored) {
                    }
                }
                throw new UploadLimitExceededException(
                        "Upload is larger than the allowed " + limit + " bytes", limit);
            }
        }
    }
}
