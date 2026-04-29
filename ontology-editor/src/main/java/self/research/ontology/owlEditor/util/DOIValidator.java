package self.research.ontology.owlEditor.util;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple DOI normalization, format validation and optional Crossref existence check.
 */
public class DOIValidator {
    private static final Logger log = LoggerFactory.getLogger(DOIValidator.class);

    private DOIValidator() {}

    private static final Pattern DOI_REGEX = Pattern.compile(
            "^10\\.\\d{4,9}/[-._;()/:A-Z0-9]+$",
            Pattern.CASE_INSENSITIVE);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // strip common DOI URL wrappers
        s = s.replaceAll("(?i)^https?://(dx\\.)?doi\\.org/", "");
        // percent-decode if possible
        try {
            s = URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            // ignore and use original
        }
        // strip trailing punctuation commonly added when pasted
        s = s.replaceAll("[.,;:)\\]\\}]+$", "");
        return s;
    }

    public static boolean isValidFormat(String raw) {
        String doi = normalize(raw);
        if (doi.isEmpty()) return false;
        return DOI_REGEX.matcher(doi).matches();
    }

    public static class ValidationResult {
        public final boolean ok;
        public final String reason; // "malformed", "not-found", "rate-limited", "service-error"
        public final Integer statusCode; // HTTP status if applicable
        public final Long retryAfterSeconds; // suggested wait for rate-limit

        public ValidationResult(boolean ok, String reason, Integer statusCode, Long retryAfterSeconds) {
            this.ok = ok;
            this.reason = reason;
            this.statusCode = statusCode;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public static ValidationResult ok() { return new ValidationResult(true, null, 200, null); }
        public static ValidationResult malformed() { return new ValidationResult(false, "malformed", null, null); }
        public static ValidationResult notFound() { return new ValidationResult(false, "not-found", 404, null); }
        public static ValidationResult rateLimited(Long retryAfter) { return new ValidationResult(false, "rate-limited", 429, retryAfter); }
        public static ValidationResult serviceError(int status) { return new ValidationResult(false, "service-error", status, null); }
    }

    /**
     * Check DOI existence via Crossref REST API. This performs network calls and
     * respects Retry-After for 429 responses. Does not throw on normal HTTP errors.
     *
     * @param raw DOI or DOI-wrapped string
     * @param maxRetries number of retry attempts on 429 (exponential backoff)
     */
    public static ValidationResult checkExistsCrossref(String raw, int maxRetries) {
        String doi = normalize(raw);
        if (!isValidFormat(doi)) return ValidationResult.malformed();

        String encoded;
        try {
            encoded = URLEncoder.encode(doi, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            encoded = doi;
        }

        String url = "https://api.crossref.org/works/" + encoded;

        int attempt = 0;
        long backoffMs = 500L;
        while (true) {
            attempt++;
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "OntoCode/1.0 (mailto:you@domain)")
                        .GET()
                        .build();

                HttpResponse<Void> resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
                int status = resp.statusCode();
                if (status == 200) return ValidationResult.ok();
                if (status == 404) return ValidationResult.notFound();
                if (status == 429) {
                    String ra = resp.headers().firstValue("Retry-After").orElse(null);
                    Long retryAfter = null;
                    if (ra != null) {
                        try { retryAfter = Long.parseLong(ra); } catch (NumberFormatException nfe) { retryAfter = null; }
                    }
                    if (attempt <= maxRetries) {
                        long sleep = backoffMs + (long)(Math.random() * 100);
                        try { Thread.sleep(sleep); } catch (InterruptedException ignored) {}
                        backoffMs = Math.min(backoffMs * 2, 10_000L);
                        continue;
                    }
                    return ValidationResult.rateLimited(retryAfter);
                }
                return ValidationResult.serviceError(status);

            } catch (IOException | InterruptedException e) {
                log.warn("Crossref check failed (attempt {}): {}", attempt, e.getMessage());
                if (attempt <= maxRetries) {
                    try { Thread.sleep(backoffMs); } catch (InterruptedException ignored) {}
                    backoffMs = Math.min(backoffMs * 2, 10_000L);
                    continue;
                }
                return new ValidationResult(false, "network-error", null, null);
            }
        }
    }
}
