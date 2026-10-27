package self.research.ontology.reasoner.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class SavedRulesClient {

    private static final Logger log = LoggerFactory.getLogger(SavedRulesClient.class);

    public static final String UNAVAILABLE_NOTE =
            "Saved SWRL rules could not be loaded, so only rules inside the ontology were applied.";

    private static final String TOKEN_HEADER = "X-Ontocode-Internal-Token";
    private static final String FINGERPRINT_HEADER = "x-ontocode-rules-fingerprint";
    private static final String DEFAULT_INTERNAL_TOKEN = "ontocode-internal";
    private static final long RETRY_AFTER_MS = 60_000L;
    private static final long STARTUP_WAIT_MS = 45_000L;

    public record SavedRules(Set<SWRLRule> rules, String fingerprint) {

        public String keyPart() {
            if (rules.isEmpty()) {
                return "";
            }
            if (fingerprint != null && !fingerprint.isBlank()) {
                return fingerprint;
            }
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                rules.stream().map(rule -> rule.getAxiomWithoutAnnotations().toString()).sorted()
                        .forEach(text -> {
                            digest.update(text.getBytes(StandardCharsets.UTF_8));
                            digest.update((byte) 10);
                        });
                return HexFormat.of().formatHex(digest.digest());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public record HttpAnswer(int status, Map<String, String> headers, String body) {
    }

    @FunctionalInterface
    public interface Transport {
        HttpAnswer get(String url, String internalToken) throws Exception;
    }

    private final Transport transport;
    private final String baseUrl;
    private final String internalToken;
    private final long startupWaitMs;
    private final long retryAfterMs;
    private volatile long downUntil;

    @Autowired
    public SavedRulesClient() {
        this(new JdkTransport(), configuredBaseUrl(), System.getenv("ONTOCODE_INTERNAL_TOKEN"), STARTUP_WAIT_MS,
                RETRY_AFTER_MS);
    }

    public SavedRulesClient(Transport transport, String baseUrl, String internalToken) {
        this(transport, baseUrl, internalToken, 0L, RETRY_AFTER_MS);
    }

    SavedRulesClient(Transport transport, String baseUrl, String internalToken, long startupWaitMs, long retryAfterMs) {
        this.transport = transport;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.internalToken = internalToken == null || internalToken.isBlank() ? DEFAULT_INTERNAL_TOKEN : internalToken;
        this.startupWaitMs = startupWaitMs;
        this.retryAfterMs = retryAfterMs;
    }

    public static SavedRulesClient none() {
        return new SavedRulesClient((url, token) -> new HttpAnswer(200, Map.of(), ""), "http://none", null);
    }

    private static String configuredBaseUrl() {
        String url = System.getenv("ONTOCODE_SWRL_URL");
        return url == null || url.isBlank() ? "http://127.0.0.1:18085" : url;
    }

    public Optional<SavedRules> load(String projectId, String draftUserId) {
        if (baseUrl.isEmpty() || System.currentTimeMillis() < downUntil) {
            return Optional.empty();
        }
        boolean draft = draftUserId != null && !draftUserId.isBlank();
        StringBuilder url = new StringBuilder(baseUrl).append("/api/swrl/")
                .append(URLEncoder.encode(projectId, StandardCharsets.UTF_8).replace("+", "%20"))
                .append("/rules/owl?draft=").append(draft);
        if (draft) {
            url.append("&userId=").append(URLEncoder.encode(draftUserId, StandardCharsets.UTF_8));
        }
        try {
            HttpAnswer answer = transport.get(url.toString(), internalToken);
            long startingUntil = System.currentTimeMillis() + startupWaitMs;
            while ((answer.status() == 502 || answer.status() == 503) && System.currentTimeMillis() < startingUntil) {
                Thread.sleep(3000);
                answer = transport.get(url.toString(), internalToken);
            }
            if (answer.status() != 200) {
                log.warn("Saved SWRL rules request answered HTTP {} for project {}", answer.status(), projectId);
                downUntil = System.currentTimeMillis() + retryAfterMs;
                return Optional.empty();
            }
            return Optional.of(parse(answer));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Saved SWRL rules could not be loaded for project {}: {}", projectId, e.toString());
            downUntil = System.currentTimeMillis() + retryAfterMs;
            return Optional.empty();
        }
    }

    private static SavedRules parse(HttpAnswer answer) throws Exception {
        String body = answer.body() == null ? "" : answer.body();
        Set<SWRLRule> rules = new LinkedHashSet<>();
        if (!body.isBlank()) {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology parsed = manager.loadOntologyFromOntologyDocument(new StringDocumentSource(body));
            parsed.axioms(AxiomType.SWRL_RULE).forEach(rules::add);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (answer.headers() != null) {
            answer.headers().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v));
        }
        String fingerprint = headers.get(FINGERPRINT_HEADER);
        return new SavedRules(rules, fingerprint == null ? "" : fingerprint);
    }

    private static final class JdkTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();

        @Override
        public HttpAnswer get(String url, String internalToken) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(25))
                    .header(TOKEN_HEADER, internalToken)
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, String> headers = new LinkedHashMap<>();
            response.headers().map().forEach((k, v) -> {
                if (!v.isEmpty()) {
                    headers.put(k, v.get(0));
                }
            });
            return new HttpAnswer(response.statusCode(), headers, response.body());
        }
    }
}
