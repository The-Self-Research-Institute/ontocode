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
import self.research.ontology.owlEditor.service.AccessDenial;
import self.research.ontology.owlEditor.service.JobAccess;
import self.research.ontology.owlEditor.service.ProjectAccessService;
import self.research.ontology.owlEditor.service.ProjectAuthorization;
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

    private final JwtVerifier jwtVerifier;
    private final ProjectAuthorization authorization;
    private final JobAccess jobAccess;
    private final ProjectAccessService projectAccess;
    private final RuleRunStore runStore;
    private final AssistantEditGroupRepository editGroups;

    @Value("${ontocode.editor.require-jwt:false}")
    private boolean requireJwt;

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    public WebSocketAuthChannelInterceptor(JwtVerifier jwtVerifier,
                                           ProjectAuthorization authorization,
                                           JobAccess jobAccess,
                                           ProjectAccessService projectAccess,
                                           RuleRunStore runStore,
                                           AssistantEditGroupRepository editGroups) {
        this.jwtVerifier = jwtVerifier;
        this.authorization = authorization;
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
        if (StompCommand.SUBSCRIBE.equals(command)) {
            String destination = accessor.getDestination();
            if (!allowSubscribe(destination, accessor)) {
                log.warn("[WS-Auth] Refused SUBSCRIBE to {} for session {}", destination, accessor.getSessionId());
                return null;
            }
        } else if (StompCommand.SEND.equals(command)) {
            refuseIfDenied(sendDenial(accessor));
        }
        return message;
    }

    private boolean desktop() {
        return desktopMode || authorization.isDesktopMode();
    }

    private void connect(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (!JwtVerifier.isBearer(authHeader)) {
            if (requireJwt && !desktop()) {
                throw new MessageDeliveryException("Authentication required");
            }
            return;
        }
        if (!jwtVerifier.configured()) {
            legacyAttributes(accessor, authHeader.substring("Bearer ".length()));
            return;
        }
        Claims claims;
        try {
            claims = jwtVerifier.verify(authHeader);
        } catch (RuntimeException e) {
            log.debug("[WS-Auth] Rejected CONNECT with an invalid token: {}", e.getMessage());
            throw new MessageDeliveryException("Invalid or expired token");
        }
        String subject = claims.getSubject();
        String userId = text(claims.get("userId"));
        String plan = text(claims.get("plan"));
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs != null) {
            attrs.put("plan", plan != null ? plan : "FREE");
            if (userId != null) {
                attrs.put("userId", userId);
            }
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
        String[] parts = token.trim().split("\\.");
        if (parts.length != 3) {
            return;
        }
        try {
            JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            Map<String, Object> attrs = accessor.getSessionAttributes();
            if (attrs != null) {
                attrs.put("plan", claims.has("plan") ? claims.get("plan").asText() : "FREE");
                if (claims.hasNonNull("userId")) {
                    attrs.put("userId", claims.get("userId").asText());
                }
                attrs.put("username", claims.has("sub") ? claims.get("sub").asText() : "anonymous");
            }
        } catch (Exception e) {
            log.debug("[WS-Auth] Could not decode JWT from CONNECT: {}", e.getMessage());
        }
    }

    private static boolean verified(StompHeaderAccessor accessor) {
        Map<String, Object> attrs = accessor.getSessionAttributes();
        return attrs != null && Boolean.TRUE.equals(attrs.get(VERIFIED_ATTRIBUTE));
    }

    private static CallerIdentity callerOf(StompHeaderAccessor accessor) {
        return verified(accessor)
                ? CallerIdentity.ofSession(accessor.getSessionAttributes())
                : new CallerIdentity(null, null);
    }

    private boolean allowSubscribe(String destination, StompHeaderAccessor accessor) {
        if (StompDestinations.isPattern(destination)) {
            return false;
        }
        if (desktop()) {
            return true;
        }
        if (!verified(accessor)) {
            return !requireJwt;
        }
        Decision decision = decide(destination, callerOf(accessor));
        return decision == Decision.ALLOW || (decision == Decision.UNKNOWN && !requireJwt);
    }

    private Decision decide(String destination, CallerIdentity caller) {
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
            return projectTopic(id, caller) ? Decision.ALLOW : Decision.DENY;
        }
        if (JOB_TOPICS.contains("|" + kind + "|") && seg.length == 4) {
            JobAccess.Caller jobCaller = new JobAccess.Caller(ownerKey(caller), caller.email(), false);
            return jobAccess.check(jobCaller, null, id, null).allowed() ? Decision.ALLOW : Decision.DENY;
        }
        if ("run".equals(kind) && seg.length == 4) {
            return run(id, caller) ? Decision.ALLOW : Decision.DENY;
        }
        if ("assistant".equals(kind)) {
            return seg.length == 5 && "consistency-check".equals(id) && editGroup(seg[4], caller)
                    ? Decision.ALLOW : Decision.DENY;
        }
        if ("shares".equals(kind) && seg.length == 4) {
            return caller.email() != null && caller.email().equalsIgnoreCase(id) ? Decision.ALLOW : Decision.DENY;
        }
        if ("workspace".equals(kind) && seg.length == 4) {
            return Decision.ALLOW;
        }
        return Decision.UNKNOWN;
    }

    private static String ownerKey(CallerIdentity caller) {
        return caller.userId() != null ? caller.userId() : caller.email();
    }

    private boolean projectTopic(String projectId, CallerIdentity caller) {
        if (authorization.noAccessTo(caller, projectId).isEmpty()) {
            return true;
        }
        return !requireJwt && !projectAccess.projectExists(projectId);
    }

    private boolean run(String runId, CallerIdentity caller) {
        Optional<RuleRunStore.Run> run = runStore.find(runId);
        return run.isPresent() && run.get().ownerId().equals(ownerKey(caller))
                && authorization.noAccessTo(caller, run.get().projectId()).isEmpty();
    }

    private boolean editGroup(String groupId, CallerIdentity caller) {
        return editGroups.findById(groupId)
                .filter(group -> caller.email() != null && caller.email().equalsIgnoreCase(group.getUserEmail()))
                .filter(group -> group.getProjectId() == null
                        || authorization.noAccessTo(caller, group.getProjectId()).isEmpty())
                .isPresent();
    }

    private Optional<AccessDenial> sendDenial(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (StompDestinations.isBrokerDestination(destination)) {
            return Optional.of(AccessDenial.forbidden("Clients cannot publish to " + destination));
        }
        CallerIdentity caller = callerOf(accessor);
        if (!requireJwt && !caller.signedIn()) {
            return Optional.empty();
        }
        Optional<String> projectId = StompDestinations.projectIdOf(destination);
        if (projectId.isEmpty()) {
            return Optional.empty();
        }
        return authorization.noAccessTo(caller, projectId.get());
    }

    private static void refuseIfDenied(Optional<AccessDenial> denial) {
        if (denial.isPresent()) {
            throw new MessageDeliveryException(denial.get().message());
        }
    }
}
