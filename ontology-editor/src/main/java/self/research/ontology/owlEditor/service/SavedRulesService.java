package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import self.research.ontology.common.rules.OntologyRulesFingerprint;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SavedRulesService {

    private static final Logger log = LoggerFactory.getLogger(SavedRulesService.class);

    public static final String UNAVAILABLE_NOTE =
            "Saved SWRL rules could not be loaded, so only rules inside the ontology were applied.";
    public static final String UNAVAILABLE_FINGERPRINT = "saved-rules-unavailable";

    private static final String TOKEN_HEADER = "X-Ontocode-Internal-Token";
    private static final String FINGERPRINT_HEADER = "x-ontocode-rules-fingerprint";
    private static final String SKIPPED_HEADER = "x-ontocode-skipped-rules";
    private static final String DEFAULT_INTERNAL_TOKEN = "ontocode-internal";
    private static final long RETRY_AFTER_MS = 60_000L;
    private static final long STARTUP_WAIT_MS = 45_000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record SkippedSavedRule(String ruleName, String reason) {
    }

    public record SavedRules(Set<SWRLRule> rules, String fingerprint, List<SkippedSavedRule> skipped) {
    }

    public record HttpAnswer(int status, Map<String, String> headers, String body) {
    }

    @FunctionalInterface
    public interface Transport {
        HttpAnswer get(String url, String internalToken) throws Exception;
    }

    public record Merged(OWLOntology ontology, String fingerprint, boolean savedRulesUnavailable,
                         List<SkippedSavedRule> skipped, int added) {

        public static Merged plain(OWLOntology ontology) {
            return new Merged(ontology, OntologyRulesFingerprint.of(ontology), false, List.of(), 0);
        }
    }

    private final Transport transport;
    private final String baseUrl;
    private final String internalToken;
    private final long startupWaitMs;
    private final long retryAfterMs;
    private volatile long downUntil;

    @Autowired
    public SavedRulesService() {
        this(new JdkTransport(), configuredBaseUrl(), System.getenv("ONTOCODE_INTERNAL_TOKEN"), STARTUP_WAIT_MS, RETRY_AFTER_MS);
    }

    public SavedRulesService(Transport transport, String baseUrl, String internalToken) {
        this(transport, baseUrl, internalToken, 0L, RETRY_AFTER_MS);
    }

    SavedRulesService(Transport transport, String baseUrl, String internalToken, long startupWaitMs, long retryAfterMs) {
        this.transport = transport;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.internalToken = internalToken == null || internalToken.isBlank() ? DEFAULT_INTERNAL_TOKEN : internalToken;
        this.startupWaitMs = startupWaitMs;
        this.retryAfterMs = retryAfterMs;
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

    public Merged applyTo(OWLOntology base, String projectId, String draftUserId) {
        Optional<SavedRules> loaded = load(projectId, draftUserId);
        if (loaded.isEmpty()) {
            return unavailable(base, List.of());
        }
        return merge(base, loaded.get());
    }

    public static Merged mergeOrPlain(SavedRulesService service, OWLOntology base, String projectId, String draftUserId) {
        return service == null ? Merged.plain(base) : service.applyTo(base, projectId, draftUserId);
    }

    private static Merged unavailable(OWLOntology base, List<SkippedSavedRule> skipped) {
        return new Merged(base, UNAVAILABLE_FINGERPRINT + ":" + OntologyRulesFingerprint.of(base), true, skipped, 0);
    }

    static Merged merge(OWLOntology base, SavedRules saved) {
        Set<String> existing = base.importsClosure()
                .flatMap(o -> o.axioms(AxiomType.SWRL_RULE))
                .map(rule -> rule.getAxiomWithoutAnnotations().toString())
                .collect(Collectors.toSet());
        List<SWRLRule> toAdd = new ArrayList<>();
        for (SWRLRule rule : saved.rules()) {
            if (existing.add(rule.getAxiomWithoutAnnotations().toString())) {
                toAdd.add(rule);
            }
        }
        if (toAdd.isEmpty()) {
            return new Merged(base, OntologyRulesFingerprint.of(base), false, saved.skipped(), 0);
        }
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology copy = manager.createOntology();
            Set<OWLAxiom> axioms = base.importsClosure().flatMap(OWLOntology::axioms)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            manager.addAxioms(copy, axioms);
            manager.addAxioms(copy, toAdd);
            return new Merged(copy, OntologyRulesFingerprint.of(copy), false, saved.skipped(), toAdd.size());
        } catch (Exception e) {
            log.warn("Could not merge saved SWRL rules into the ontology: {}", e.toString());
            return unavailable(base, saved.skipped());
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
        Map<String, String> headers = lower(answer.headers());
        String fingerprint = headers.get(FINGERPRINT_HEADER);
        return new SavedRules(rules, fingerprint == null ? "" : fingerprint, parseSkipped(headers.get(SKIPPED_HEADER)));
    }

    private static List<SkippedSavedRule> parseSkipped(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            List<SkippedSavedRule> out = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode item : node) {
                    out.add(new SkippedSavedRule(item.path("ruleName").asText(""), item.path("reason").asText("")));
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Map<String, String> lower(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), v));
        }
        return out;
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
