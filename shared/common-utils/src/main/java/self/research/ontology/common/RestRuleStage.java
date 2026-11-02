package self.research.ontology.common;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.io.StringDocumentTarget;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLRule;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class RestRuleStage implements RuleStage {

    private static final System.Logger LOG = System.getLogger(RestRuleStage.class.getName());
    private static final int CACHE_ENTRIES = 8;
    private static final long RETRY_AFTER_MS = 60_000L;
    private static final String DEFAULT_INTERNAL_TOKEN = "ontocode-internal";
    private static final String TOKEN_HEADER = "X-Ontocode-Internal-Token";
    private static final long STARTUP_WAIT_MS = 45_000L;

    private static final ThreadLocal<String> BUSY_NOTE = new ThreadLocal<>();

    private volatile long downUntil;

    public static String busyLimitation(int ruleCount) {
        return "The rule engine is busy. " + ruleCount + " rule" + (ruleCount == 1 ? " was" : "s were") + " not run. Try again in a moment.";
    }

    public static String takeBusyLimitation() {
        String note = BUSY_NOTE.get();
        BUSY_NOTE.remove();
        return note;
    }

    private static boolean isBusy(HttpResponse<String> response) {
        int status = response.statusCode();
        return (status == 429 || status == 503) && response.body() != null
                && response.body().replaceAll("\\s", "").contains("\"busy\":true");
    }

    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient client;
    private final Map<String, Set<OWLAxiom>> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Set<OWLAxiom>> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    private final String internalToken;

    public RestRuleStage(String baseUrl, Duration timeout) {
        this(baseUrl, timeout, DEFAULT_INTERNAL_TOKEN);
    }

    public RestRuleStage(String baseUrl, Duration timeout, String internalToken) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.timeout = timeout;
        this.internalToken = internalToken == null || internalToken.isBlank() ? DEFAULT_INTERNAL_TOKEN : internalToken;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public Optional<Set<OWLAxiom>> infer(OWLOntology ontology, Collection<SWRLRule> rules) {
        BUSY_NOTE.remove();
        if (baseUrl.isEmpty() || System.currentTimeMillis() < downUntil) {
            return Optional.empty();
        }
        try {
            Set<SWRLRule> wanted = Set.copyOf(rules);
            OWLOntologyManager copyManager = OWLManager.createOWLOntologyManager();
            OWLOntology forEngine = copyManager.createOntology();
            copyManager.addAxioms(forEngine, ontology.axioms()
                    .filter(axiom -> !(axiom instanceof SWRLRule) || wanted.contains(axiom)));
            StringDocumentTarget target = new StringDocumentTarget();
            copyManager.saveOntology(forEngine, new FunctionalSyntaxDocumentFormat(), target);
            String text = target.toString();
            String key = digest(text);
            synchronized (cache) {
                Set<OWLAxiom> hit = cache.get(key);
                if (hit != null) {
                    return Optional.of(hit);
                }
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/swrl/infer/ontology-rules"))
                    .timeout(timeout)
                    .header("Content-Type", "text/plain; charset=UTF-8")
                    .header(TOKEN_HEADER, internalToken)
                    .POST(HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long startingUntil = System.currentTimeMillis() + STARTUP_WAIT_MS;
            while ((response.statusCode() == 502 || response.statusCode() == 503) && !isBusy(response)
                    && System.currentTimeMillis() < startingUntil) {
                Thread.sleep(3000);
                response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            }
            if (isBusy(response)) {
                BUSY_NOTE.set(busyLimitation(rules.size()));
                LOG.log(System.Logger.Level.INFO, "SWRL rule engine is busy (HTTP {0}), {1} rules not run", response.statusCode(), rules.size());
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                LOG.log(System.Logger.Level.WARNING, "SWRL rule engine answered HTTP {0}: {1}", response.statusCode(), abbreviate(response.body()));
                downUntil = System.currentTimeMillis() + RETRY_AFTER_MS;
                return Optional.empty();
            }
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology inferred = manager.loadOntologyFromOntologyDocument(new StringDocumentSource(response.body()));
            Set<OWLAxiom> axioms = inferred.axioms().collect(Collectors.toUnmodifiableSet());
            synchronized (cache) {
                cache.put(key, axioms);
            }
            return Optional.of(axioms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "SWRL rule engine call failed: {0}", String.valueOf(e));
            downUntil = System.currentTimeMillis() + RETRY_AFTER_MS;
            return Optional.empty();
        }
    }

    @Override
    public String describe() {
        return "SWRLAPI 2.1.3 with Drools 7.74.1 (remote rule service)";
    }

    private static volatile RestRuleStage shared;

    public static RuleStage shared() {
        RestRuleStage stage = shared;
        if (stage == null) {
            synchronized (RestRuleStage.class) {
                if (shared == null) {
                    String url = System.getenv("ONTOCODE_SWRL_URL");
                    shared = new RestRuleStage(url == null || url.isBlank() ? "http://127.0.0.1:18085" : url,
                            Duration.ofSeconds(25), System.getenv("ONTOCODE_INTERNAL_TOKEN"));
                }
                stage = shared;
            }
        }
        return stage;
    }

    private static String abbreviate(String text) {
        return text == null ? "" : text.substring(0, Math.min(text.length(), 300));
    }

    private static String digest(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
