package self.research.ontology.owlEditor.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import self.research.ontology.owlEditor.service.AssistantIdempotencyStore;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class AssistantIdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAY_HEADER = "Idempotency-Replayed";

    static final String ATTR_RECORD_ID = AssistantIdempotencyFilter.class.getName() + ".recordId";
    static final String ATTR_BODY_HASH = AssistantIdempotencyFilter.class.getName() + ".bodyHash";
    static final String ATTR_ACTOR = AssistantIdempotencyFilter.class.getName() + ".actor";
    static final String ATTR_PATH = AssistantIdempotencyFilter.class.getName() + ".path";
    static final String ATTR_OWNER = AssistantIdempotencyFilter.class.getName() + ".owner";

    private static final Pattern IDEMPOTENT_PATHS = Pattern.compile(
            "^/api/v1/code-assistant/sessions/?$"
                    + "|^/api/v1/code-assistant/sessions/[^/]+/propose/?$"
                    + "|^/api/v1/code-assistant/sessions/[^/]+/groups/[^/]+/apply/?$");

    private static final Set<Integer> NOT_REPLAYABLE = Set.of(401, 403, 408, 409, 423, 429);

    private final AssistantIdempotencyStore store;
    private final int maxRequestBytes;
    private final int maxStoredResponseBytes;

    public AssistantIdempotencyFilter(
            AssistantIdempotencyStore store,
            @Value("${assistant.idempotency.max-request-bytes:4194304}") int maxRequestBytes,
            @Value("${assistant.idempotency.max-stored-response-bytes:1048576}") int maxStoredResponseBytes) {
        this.store = store;
        this.maxRequestBytes = maxRequestBytes;
        this.maxStoredResponseBytes = maxStoredResponseBytes;
    }

    static boolean appliesTo(String method, String path) {
        return "POST".equalsIgnoreCase(method) && path != null && IDEMPOTENT_PATHS.matcher(path).matches();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String key = request.getHeader(HEADER);
        return key == null || key.isBlank() || !appliesTo(request.getMethod(), pathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> actor = JwtIdentityExtractor.extractEmail(request);
        if (actor.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        InputStream original = request.getInputStream();
        byte[] prefix = original.readNBytes(maxRequestBytes + 1);
        if (prefix.length > maxRequestBytes) {
            log.warn("[AssistantIdempotency] Request body over {} bytes; handling {} without idempotency",
                    maxRequestBytes, pathWithinApplication(request));
            chain.doFilter(new ReplayableBodyRequest(request, prefix, original), response);
            return;
        }

        String path = pathWithinApplication(request);
        String key = request.getHeader(HEADER).trim();
        ReplayableBodyRequest buffered = new ReplayableBodyRequest(request, prefix, null);
        buffered.setAttribute(ATTR_RECORD_ID, sha256Hex(actor.get() + "\n" + request.getMethod().toUpperCase()
                + "\n" + path + "\n" + key));
        buffered.setAttribute(ATTR_BODY_HASH, sha256Hex(prefix));
        buffered.setAttribute(ATTR_ACTOR, actor.get());
        buffered.setAttribute(ATTR_PATH, path);

        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
        boolean finished = false;
        try {
            chain.doFilter(buffered, captured);
            finished = true;
        } finally {
            settle(buffered, captured, finished);
            captured.copyBodyToResponse();
        }
    }

    private void settle(HttpServletRequest request, ContentCachingResponseWrapper captured, boolean finished) {
        Object owner = request.getAttribute(ATTR_OWNER);
        if (owner == null) {
            return;
        }
        String recordId = (String) request.getAttribute(ATTR_RECORD_ID);
        try {
            int status = captured.getStatus();
            byte[] body = captured.getContentAsByteArray();
            if (!finished || !replayable(status) || body.length > maxStoredResponseBytes) {
                store.abandon(recordId, owner.toString());
                return;
            }
            store.complete(recordId, owner.toString(), status, captured.getContentType(),
                    new String(body, StandardCharsets.UTF_8), Instant.now());
        } catch (Exception e) {
            log.warn("[AssistantIdempotency] Could not settle record {}: {}", recordId, e.getMessage());
        }
    }

    static boolean replayable(int status) {
        return status < 500 && !NOT_REPLAYABLE.contains(status);
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (uri == null) {
            return null;
        }
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class ReplayableBodyRequest extends HttpServletRequestWrapper {
        private final byte[] prefix;
        private final InputStream remainder;
        private ServletInputStream stream;

        ReplayableBodyRequest(HttpServletRequest request, byte[] prefix, InputStream remainder) {
            super(request);
            this.prefix = prefix;
            this.remainder = remainder;
        }

        @Override
        public ServletInputStream getInputStream() {
            if (stream == null) {
                InputStream source = remainder == null
                        ? new ByteArrayInputStream(prefix)
                        : new SequenceInputStream(new ByteArrayInputStream(prefix), remainder);
                stream = new DelegatingServletInputStream(source, remainder == null);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return remainder == null ? prefix.length : super.getContentLength();
        }

        @Override
        public long getContentLengthLong() {
            return remainder == null ? prefix.length : super.getContentLengthLong();
        }
    }

    private static final class DelegatingServletInputStream extends ServletInputStream {
        private final InputStream source;
        private final boolean fullyBuffered;
        private boolean finished;

        DelegatingServletInputStream(InputStream source, boolean fullyBuffered) {
            this.source = source;
            this.fullyBuffered = fullyBuffered;
        }

        @Override
        public int read() throws IOException {
            int b = source.read();
            if (b < 0) {
                finished = true;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = source.read(b, off, len);
            if (n < 0) {
                finished = true;
            }
            return n;
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public boolean isReady() {
            return fullyBuffered || !finished;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("Async reads are not supported for idempotent assistant requests");
        }
    }
}
