package self.research.ontology.swrl.config;

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

@Component
@Order(2)
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestResponseLoggingFilter.class);

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
