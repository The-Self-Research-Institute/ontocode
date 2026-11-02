package self.research.ontology.owlEditor.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.repository.AssistantEditGroupRepository;
import self.research.ontology.owlEditor.service.JobAccess;
import self.research.ontology.owlEditor.service.ProjectAccessService;
import self.research.ontology.owlEditor.service.RuleRunStore;

import java.security.Principal;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    public static final String VERIFIED_ATTRIBUTE = "wsVerified";
    public static final String EMAIL_ATTRIBUTE = "email";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PROJECT_TOPICS = "|ontology|presence|locks|graph|import|queue|cursor|";
    private static final String JOB_TOPICS = "|reasoning|dlquery|";

    public record WsPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }

    private enum Decision { ALLOW, DENY, UNKNOWN }

    private record Session(String userId, String email) {
    }

    private final JobAccess jobAccess;
    private final ProjectAccessService projectAccess;
    private final RuleRunStore runStore;
    private final AssistantEditGroupRepository editGroups;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${ontocode.editor.require-jwt:false}")
    private boolean requireJwt;

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    public WebSocketAuthChannelInterceptor(JobAccess jobAccess,
                                           ProjectAccessService projectAccess,
                                           RuleRunStore runStore,
                                           AssistantEditGroupRepository editGroups) {
        this.jobAccess = jobAccess;
        this.projectAccess = projectAccess;
        this.runStore = runStore;
        this.editGroups = editGroups;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        boolean rebuild = accessor == null || !accessor.isMutable();
        if (rebuild) {
            accessor = StompHeaderAccessor.wrap(message);
        }
        StompCommand command = accessor.getCommand();

        if (StompCommand.CONNECT.equals(command)) {
            connect(accessor);
            return rebuild ? MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders()) : message;
        }
        if (StompCommand.SUBSCRIBE.equals(command) && enforcing()) {
            String destination = accessor.getDestination();
            if (!allowSubscribe(destination, session(accessor))) {
                log.warn("[WS-Auth] Refused SUBSCRIBE to {} for session {}", destination, accessor.getSessionId());
                return null;
            }
        }
        return message;
    }

    private boolean enforcing() {
        return !desktopMode && jwtSecret != null && !jwtSecret.isBlank();
    }

    private void connect(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        boolean hasBearer = authHeader != null && authHeader.startsWith("Bearer ");
        if (!enforcing()) {
            if (hasBearer) {
                legacyAttributes(accessor, authHeader.substring(7));
            }
            return;
        }
        if (!hasBearer) {
            if (requireJwt) {
                throw new MessageDeliveryException("Authentication required");
            }
            return;
        }
        Claims claims;
        try {
            claims = EditorJwtVerifier.verify(jwtSecret, authHeader.substring(7));
        } catch (Exception e) {
            log.debug("[WS-Auth] Rejected CONNECT token: {}", e.getMessage());
            if (requireJwt) {
                throw new MessageDeliveryException("Invalid or expired token");
            }
            return;
        }
        String subject = claims.getSubject();
        String userId = text(claims.get("userId"));
        String plan = text(claims.get("plan"));
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs != null) {
            attrs.put("plan", plan != null ? plan : "FREE");
            attrs.put("userId", userId);
            attrs.put("username", subject);
            attrs.put(EMAIL_ATTRIBUTE, subject);
            attrs.put(VERIFIED_ATTRIBUTE, Boolean.TRUE);
        }
        accessor.setUser(new WsPrincipal(userId != null ? userId : subject));
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private void legacyAttributes(StompHeaderAccessor accessor, String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return;
        }
        try {
            JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            Map<String, Object> attrs = accessor.getSessionAttributes();
            if (attrs != null) {
                attrs.put("plan", claims.has("plan") ? claims.get("plan").asText() : "FREE");
                attrs.put("userId", claims.has("userId") ? claims.get("userId").asText() : null);
                attrs.put("username", claims.has("sub") ? claims.get("sub").asText() : "anonymous");
            }
        } catch (Exception e) {
            log.debug("[WS-Auth] Could not decode JWT from CONNECT: {}", e.getMessage());
        }
    }

    private static Optional<Session> session(StompHeaderAccessor accessor) {
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs == null || !Boolean.TRUE.equals(attrs.get(VERIFIED_ATTRIBUTE))) {
            return Optional.empty();
        }
        Object userId = attrs.get("userId");
        Object email = attrs.get(EMAIL_ATTRIBUTE);
        String id = userId != null ? userId.toString() : null;
        String mail = email != null ? email.toString() : null;
        return Optional.of(new Session(id != null ? id : mail, mail));
    }

    private boolean allowSubscribe(String destination, Optional<Session> session) {
        if (session.isEmpty()) {
            return !requireJwt;
        }
        Decision decision = decide(destination, session.get());
        return decision == Decision.ALLOW || (decision == Decision.UNKNOWN && !requireJwt);
    }

    private Decision decide(String destination, Session session) {
        if (destination == null || destination.isBlank()) {
            return Decision.DENY;
        }
        if (destination.startsWith("/user/")) {
            return destination.startsWith("/user/queue/") ? Decision.ALLOW : Decision.DENY;
        }
        if (destination.startsWith("/queue/")) {
            return Decision.DENY;
        }
        String[] seg = destination.split("/");
        if (seg.length < 4 || !"topic".equals(seg[1]) || seg[2].isEmpty()) {
            return Decision.UNKNOWN;
        }
        String kind = seg[2];
        String id = seg[3];
        if (id.isEmpty()) {
            return Decision.DENY;
        }
        if (("queue".equals(kind) || "dlquery".equals(kind)) && "stats".equals(id) && seg.length == 4) {
            return Decision.ALLOW;
        }
        if (PROJECT_TOPICS.contains("|" + kind + "|") && seg.length == 4) {
            return projectTopic(id, session) ? Decision.ALLOW : Decision.DENY;
        }
        if (JOB_TOPICS.contains("|" + kind + "|") && seg.length == 4) {
            JobAccess.Caller caller = new JobAccess.Caller(session.userId(), session.email(), false);
            return jobAccess.check(caller, null, id, null).allowed() ? Decision.ALLOW : Decision.DENY;
        }
        if ("run".equals(kind) && seg.length == 4) {
            return run(id, session) ? Decision.ALLOW : Decision.DENY;
        }
        if ("assistant".equals(kind)) {
            return seg.length == 5 && "consistency-check".equals(id) && editGroup(seg[4], session)
                    ? Decision.ALLOW : Decision.DENY;
        }
        if ("shares".equals(kind) && seg.length == 4) {
            return session.email() != null && session.email().equalsIgnoreCase(id) ? Decision.ALLOW : Decision.DENY;
        }
        if ("workspace".equals(kind) && seg.length == 4) {
            return Decision.ALLOW;
        }
        return Decision.UNKNOWN;
    }

    private boolean projectTopic(String projectId, Session session) {
        if (session.email() != null && projectAccess.hasProjectAccess(projectId, session.email())) {
            return true;
        }
        return !requireJwt && !projectAccess.projectExists(projectId);
    }

    private boolean run(String runId, Session session) {
        Optional<RuleRunStore.Run> run = runStore.find(runId);
        return run.isPresent() && run.get().ownerId().equals(session.userId())
                && session.email() != null && projectAccess.hasProjectAccess(run.get().projectId(), session.email());
    }

    private boolean editGroup(String groupId, Session session) {
        return editGroups.findById(groupId)
                .filter(group -> session.email() != null && session.email().equalsIgnoreCase(group.getUserEmail()))
                .filter(group -> group.getProjectId() == null
                        || projectAccess.hasProjectAccess(group.getProjectId(), session.email()))
                .isPresent();
    }
}
