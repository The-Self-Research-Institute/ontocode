package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;

final class ProviderStreamRelay {

    private static final Logger log = LoggerFactory.getLogger(ProviderStreamRelay.class);
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE = new ParameterizedTypeReference<>() {
    };

    private record UpstreamFailure(int status, String body, String retryAfter) {
    }

    private record WholeReply(String body) {
    }

    private final ProviderResponseFilter filter;
    private final AssistantProviderProxyService proxy;

    ProviderStreamRelay(ProviderResponseFilter filter, AssistantProviderProxyService proxy) {
        this.filter = filter;
        this.proxy = proxy;
    }

    void relay(WebClient webClient, String provider, String model, String key, byte[] payload,
               AssistantProviderProxyService.StreamSink sink) throws IOException {
        Flux<Object> events = webClient.post()
                .uri(ProviderRequestShaper.uri(provider, model, proxy.baseUrlOverride(), true))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                .headers(headers -> ProviderRequestShaper.applyAuthHeaders(provider, key, headers))
                .bodyValue(payload)
                .exchangeToFlux(ProviderStreamRelay::responseItems)
                .timeout(Duration.ofSeconds(proxy.responseTimeoutSeconds() + 5));
        long started = System.nanoTime();
        int relayed = 0;
        try {
            for (Object item : events.toIterable()) {
                if (item instanceof UpstreamFailure failure) {
                    reportFailure(provider, failure, sink);
                    return;
                }
                if (item instanceof WholeReply whole) {
                    sink.complete(filter.filter(provider, 200, whole.body()));
                    return;
                }
                String data = ((ServerSentEvent<?>) item).data() instanceof String text ? text : null;
                if (data != null && !data.isBlank()) {
                    sink.event(filter.filterStreamEvent(provider, data));
                    relayed++;
                }
            }
            log.info("[Assistant] [PERF] managed stream provider={} events={} total={}ms", provider, relayed,
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[Assistant] Managed provider stream to {} failed: {}", provider, e.getClass().getSimpleName());
            sink.error(503, "PROVIDER_UNAVAILABLE", "The AI provider could not be reached", null);
        }
    }

    private static Flux<Object> responseItems(ClientResponse response) {
        int status = response.statusCode().value();
        boolean eventStream = response.headers().contentType()
                .map(MediaType.TEXT_EVENT_STREAM::isCompatibleWith).orElse(false);
        if (status >= 200 && status < 300 && eventStream) {
            return response.bodyToFlux(SSE).map(event -> (Object) event);
        }
        if (status >= 200 && status < 300) {
            return response.bodyToMono(String.class).defaultIfEmpty("{}")
                    .map(body -> (Object) new WholeReply(body)).flux();
        }
        String retryAfter = response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        return response.bodyToMono(String.class).defaultIfEmpty("")
                .map(body -> (Object) new UpstreamFailure(status, body, retryAfter)).flux();
    }

    private void reportFailure(String provider, UpstreamFailure failure,
                               AssistantProviderProxyService.StreamSink sink) throws IOException {
        int status = failure.status();
        Long retryAfter = parseRetryAfter(failure.retryAfter());
        if (status == 401 || status == 403) {
            log.error("[Assistant] Managed provider {} rejected the server credentials with status {}", provider, status);
            sink.error(503, "PROVIDER_UNAVAILABLE", "The AI provider rejected the server's credentials", null);
            return;
        }
        String message = filter.upstreamErrorMessage(provider, status, failure.body());
        if (status >= 500) {
            sink.error(503, "PROVIDER_UNAVAILABLE", "The AI provider is temporarily unavailable, try again shortly",
                    retryAfter);
            return;
        }
        sink.error(status, null, message, retryAfter);
    }

    private static Long parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds >= 0 && seconds <= 3600 ? seconds : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
