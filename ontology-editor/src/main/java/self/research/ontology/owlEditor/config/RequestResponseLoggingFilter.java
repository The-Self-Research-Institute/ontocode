package self.research.ontology.owlEditor.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Per-request access log for the editor service.
 *
 * <p>Logs ONE line per HTTP request to the regular application log (app.log).
 * Captures the metadata operators need to trace a user's activity end-to-end:
 * <ul>
 *   <li>method &amp; URI</li>
 *   <li>query string with sensitive values redacted</li>
 *   <li>request {@code Content-Type} and {@code Content-Length}</li>
 *   <li>response status</li>
 *   <li>response {@code Content-Type} and {@code Content-Length}</li>
 *   <li>elapsed time in ms</li>
 * </ul>
 *
 * <p><b>What we deliberately do NOT log:</b>
 * <ul>
 *   <li>Request bodies — we don't wrap the request, so the body is streamed
 *       to the controller and never copied to a log buffer.</li>
 *   <li>Response bodies — same.</li>
 *   <li>{@code Authorization} / {@code Cookie} / any header values.</li>
 *   <li>Values of sensitive query params (token, password, email, …).</li>
 * </ul>
 *
 * <p>Order(2) so it runs <em>after</em> {@link MdcLoggingFilter} (Order=1)
 * — the log line is then automatically tagged with the cascading
 * {@code [%X{ctx}]} block populated by that filter.
 */
@Component("owlEditorRequestResponseLoggingFilter")
@Order(2)
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestResponseLoggingFilter.class);

    /** Substring-matched against query parameter names (case-insensitive). */
    private static final Set<String> SENSITIVE_PARAM_NEEDLES = Set.of(
            "token", "password", "passwd", "secret", "apikey", "api_key",
            "key", "auth", "authorization", "code", "signature", "sig",
            "otp", "jwt", "session", "email", "card", "cvv", "ssn"
    );
    private static final String REDACTED = "***REDACTED***";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startNs = System.nanoTime();
        // Wrap the response just enough to capture the bytes written.
        ByteCountingResponseWrapper wrapped = new ByteCountingResponseWrapper(response);
        Throwable failure = null;
        try {
            filterChain.doFilter(request, wrapped);
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            emit(request, wrapped, startNs, failure);
        }
    }

    private void emit(HttpServletRequest request, ByteCountingResponseWrapper response,
                       long startNs, Throwable failure) {
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        String method = request.getMethod();
        String uri = request.getRequestURI();
        String query = redactQuery(request.getQueryString());
        String fullPath = query != null ? uri + "?" + query : uri;

        String reqCt = nullToDash(request.getContentType());
        long reqLen = request.getContentLengthLong();
        String reqLenStr = reqLen >= 0 ? reqLen + "B" : "-";

        int status = response.getStatus();
        String resCt = nullToDash(response.getContentType());
        long resLen = response.getBytesWritten();
        String resLenStr = resLen >= 0 ? resLen + "B" : "-";

        // Single structured line. Keys in the message map directly to the
        // ones used in `[ctx]` (set by MdcLoggingFilter) for grep-friendliness.
        if (failure != null) {
            log.error("[REQ] {} {} req_ct={} req_len={} -> FAILED after {}ms ({}: {})",
                    method, fullPath, reqCt, reqLenStr, elapsedMs,
                    failure.getClass().getSimpleName(), failure.getMessage());
            return;
        }

        if (status >= 500) {
            log.error("[REQ] {} {} req_ct={} req_len={} -> {} res_ct={} res_len={} ({}ms)",
                    method, fullPath, reqCt, reqLenStr, status, resCt, resLenStr, elapsedMs);
        } else if (status >= 400) {
            log.warn("[REQ] {} {} req_ct={} req_len={} -> {} res_ct={} res_len={} ({}ms)",
                    method, fullPath, reqCt, reqLenStr, status, resCt, resLenStr, elapsedMs);
        } else {
            log.info("[REQ] {} {} req_ct={} req_len={} -> {} res_ct={} res_len={} ({}ms)",
                    method, fullPath, reqCt, reqLenStr, status, resCt, resLenStr, elapsedMs);
        }
    }

    private static String nullToDash(String s) {
        return (s == null || s.isEmpty()) ? "-" : s;
    }

    /**
     * Redact values of sensitive query params. Names are kept so triage can
     * still tell which params were sent without seeing their contents.
     */
    static String redactQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return null;
        String[] parts = rawQuery.split("&");
        StringBuilder out = new StringBuilder(rawQuery.length());
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append('&');
            String part = parts[i];
            int eq = part.indexOf('=');
            if (eq < 0) {
                out.append(part);
                continue;
            }
            String name = part.substring(0, eq);
            String lowered = name.toLowerCase();
            boolean sensitive = false;
            for (String needle : SENSITIVE_PARAM_NEEDLES) {
                if (lowered.contains(needle)) {
                    sensitive = true;
                    break;
                }
            }
            out.append(name).append('=');
            out.append(sensitive ? REDACTED : part.substring(eq + 1));
        }
        return out.toString();
    }

    // ─────────────────────────────────────────────────────────────────
    // Response wrapper that counts bytes without copying them.
    // We deliberately do NOT cache the body — that would let us log
    // response payloads, which is the thing we don't want to do.
    // ─────────────────────────────────────────────────────────────────

    private static final class ByteCountingResponseWrapper extends HttpServletResponseWrapper {
        private final CountingServletOutputStream stream;

        ByteCountingResponseWrapper(HttpServletResponse delegate) throws IOException {
            super(delegate);
            this.stream = new CountingServletOutputStream(delegate.getOutputStream());
        }

        @Override
        public jakarta.servlet.ServletOutputStream getOutputStream() {
            return stream;
        }

        @Override
        public java.io.PrintWriter getWriter() throws IOException {
            // Wrap the stream as a writer so writes from controllers using
            // PrintWriter still pass through our counter.
            return new java.io.PrintWriter(new java.io.OutputStreamWriter(
                    stream, getCharacterEncoding() != null
                            ? getCharacterEncoding()
                            : java.nio.charset.StandardCharsets.UTF_8.name()), true);
        }

        long getBytesWritten() {
            return stream.bytesWritten;
        }
    }

    private static final class CountingServletOutputStream extends jakarta.servlet.ServletOutputStream {
        private final jakarta.servlet.ServletOutputStream delegate;
        long bytesWritten = 0;

        CountingServletOutputStream(jakarta.servlet.ServletOutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isReady() { return delegate.isReady(); }

        @Override
        public void setWriteListener(jakarta.servlet.WriteListener listener) {
            delegate.setWriteListener(listener);
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            bytesWritten++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            bytesWritten += len;
        }

        @Override
        public void flush() throws IOException { delegate.flush(); }

        @Override
        public void close() throws IOException { delegate.close(); }
    }
}
