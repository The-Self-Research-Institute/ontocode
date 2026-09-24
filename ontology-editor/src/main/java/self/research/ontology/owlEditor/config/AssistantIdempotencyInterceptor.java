package self.research.ontology.owlEditor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import self.research.ontology.owlEditor.document.AssistantIdempotencyRecordDocument;
import self.research.ontology.owlEditor.document.AssistantIdempotencyRecordDocument.State;
import self.research.ontology.owlEditor.service.AssistantIdempotencyStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class AssistantIdempotencyInterceptor implements HandlerInterceptor {

    public static final Duration RETENTION = Duration.ofHours(24);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ATTEMPTS = 3;

    private final AssistantIdempotencyStore store;
    private final Duration staleInFlightAfter;
    private final Clock clock;

    public AssistantIdempotencyInterceptor(
            AssistantIdempotencyStore store,
            @Value("${assistant.idempotency.stale-in-flight-seconds:300}") long staleInFlightSeconds) {
        this(store, Duration.ofSeconds(staleInFlightSeconds), Clock.systemUTC());
    }

    AssistantIdempotencyInterceptor(AssistantIdempotencyStore store, Duration staleInFlightAfter, Clock clock) {
        this.store = store;
        this.staleInFlightAfter = staleInFlightAfter;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Object recordId = request.getAttribute(AssistantIdempotencyFilter.ATTR_RECORD_ID);
        if (recordId == null || request.getAttribute(AssistantIdempotencyFilter.ATTR_OWNER) != null) {
            return true;
        }
        String id = recordId.toString();
        String bodyHash = (String) request.getAttribute(AssistantIdempotencyFilter.ATTR_BODY_HASH);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Instant now = clock.instant();
            String owner = UUID.randomUUID().toString();
            AssistantIdempotencyRecordDocument inFlight = inFlightRecord(request, id, bodyHash, owner, now);
            if (store.tryInsert(inFlight)) {
                request.setAttribute(AssistantIdempotencyFilter.ATTR_OWNER, owner);
                return true;
            }
            Optional<AssistantIdempotencyRecordDocument> existing = store.find(id);
            if (existing.isEmpty()) {
                continue;
            }
            AssistantIdempotencyRecordDocument record = existing.get();
            boolean expired = record.getExpiresAt() != null && record.getExpiresAt().isBefore(now);
            boolean staleInFlight = record.getState() == State.IN_FLIGHT
                    && (record.getUpdatedAt() == null || record.getUpdatedAt().isBefore(now.minus(staleInFlightAfter)));
            if (expired || staleInFlight) {
                if (store.tryTakeOver(id, now.minus(staleInFlightAfter), now, inFlight)) {
                    log.info("[AssistantIdempotency] Took over {} record {} for {}",
                            expired ? "expired" : "stale in-flight", id, inFlight.getPath());
                    request.setAttribute(AssistantIdempotencyFilter.ATTR_OWNER, owner);
                    return true;
                }
                continue;
            }
            if (!bodyHash.equals(record.getBodyHash())) {
                writeError(response, 422, "This Idempotency-Key was already used with a different request body. "
                        + "Use a new key for a new request.");
                return false;
            }
            if (record.getState() == State.COMPLETED) {
                replay(response, record);
                return false;
            }
            writeError(response, 409, "A request with this Idempotency-Key is still being processed. "
                    + "Wait for it to finish, then retry with the same key to get its result.");
            return false;
        }
        writeError(response, 409, "A request with this Idempotency-Key is still being processed. "
                + "Retry with the same key shortly.");
        return false;
    }

    private AssistantIdempotencyRecordDocument inFlightRecord(HttpServletRequest request, String id, String bodyHash,
                                                              String owner, Instant now) {
        return AssistantIdempotencyRecordDocument.builder()
                .id(id)
                .actor((String) request.getAttribute(AssistantIdempotencyFilter.ATTR_ACTOR))
                .method(request.getMethod())
                .path((String) request.getAttribute(AssistantIdempotencyFilter.ATTR_PATH))
                .bodyHash(bodyHash)
                .state(State.IN_FLIGHT)
                .ownerToken(owner)
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(now.plus(RETENTION))
                .build();
    }

    private static void replay(HttpServletResponse response, AssistantIdempotencyRecordDocument record)
            throws IOException {
        response.setStatus(record.getResponseStatus() != null ? record.getResponseStatus() : 200);
        if (record.getResponseContentType() != null) {
            response.setContentType(record.getResponseContentType());
        }
        response.setHeader(AssistantIdempotencyFilter.REPLAY_HEADER, "true");
        if (record.getResponseBody() != null) {
            response.getOutputStream().write(record.getResponseBody().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("errorCode", "IDEMPOTENCY_KEY_REUSED");
        body.put("message", message);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(MAPPER.writeValueAsBytes(body));
    }
}
