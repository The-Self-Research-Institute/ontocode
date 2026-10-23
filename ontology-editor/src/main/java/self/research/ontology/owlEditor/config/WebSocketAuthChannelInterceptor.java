package self.research.ontology.owlEditor.config;

import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.service.AccessDenial;
import self.research.ontology.owlEditor.service.ProjectAuthorization;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtVerifier jwtVerifier;
    private final ProjectAuthorization authorization;
    private final boolean requireJwt;

    public WebSocketAuthChannelInterceptor(JwtVerifier jwtVerifier, ProjectAuthorization authorization,
                                           @Value("${ontocode.editor.require-jwt:false}") boolean requireJwt) {
        this.jwtVerifier = jwtVerifier;
        this.authorization = authorization;
        this.requireJwt = requireJwt;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command)) {
            authenticate(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            refuseIfDenied(subscriptionDenial(accessor));
        } else if (StompCommand.SEND.equals(command)) {
            refuseIfDenied(sendDenial(accessor));
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (!JwtVerifier.isBearer(authHeader)) {
            if (requireJwt && !authorization.isDesktopMode()) {
                throw new MessageDeliveryException("Authentication required");
            }
            return;
        }
        if (!jwtVerifier.configured()) {
            return;
        }
        Claims claims;
        try {
            claims = jwtVerifier.verify(authHeader);
        } catch (RuntimeException e) {
            log.debug("[WS-Auth] Rejected CONNECT with an invalid token: {}", e.getMessage());
            throw new MessageDeliveryException("Invalid or expired token");
        }
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs != null) {
            Object userId = claims.get("userId");
            Object plan = claims.get("plan");
            attrs.put("plan", plan == null ? "FREE" : plan.toString());
            attrs.put("userId", userId == null ? null : userId.toString());
            attrs.put("email", claims.getSubject());
            attrs.put("username", claims.getSubject());
        }
    }

    private Optional<AccessDenial> subscriptionDenial(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        CallerIdentity caller = callerOf(accessor);
        if (!StompDestinations.isBrokerDestination(destination)) {
            return Optional.empty();
        }
        if (StompDestinations.isPattern(destination)) {
            return Optional.of(AccessDenial.forbidden("Subscribing to a pattern is not allowed"));
        }
        Optional<AccessDenial> unauthenticated = requireJwt ? authorization.unauthenticated(caller) : Optional.empty();
        if (unauthenticated.isPresent()) {
            return unauthenticated;
        }
        Optional<String> sharesEmail = StompDestinations.sharesEmailOf(destination);
        if (sharesEmail.isPresent()) {
            boolean own = caller.email() != null && caller.email().equalsIgnoreCase(sharesEmail.get());
            return own || authorization.isDesktopMode()
                    ? Optional.empty()
                    : Optional.of(AccessDenial.forbidden("These notifications belong to another user"));
        }
        return projectDenial(destination, caller);
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
        return projectDenial(destination, caller);
    }

    private Optional<AccessDenial> projectDenial(String destination, CallerIdentity caller) {
        Optional<String> projectId = StompDestinations.projectIdOf(destination);
        if (projectId.isEmpty() || (!requireJwt && !caller.signedIn())) {
            return Optional.empty();
        }
        return authorization.noAccessTo(caller, projectId.get());
    }

    private static CallerIdentity callerOf(StompHeaderAccessor accessor) {
        return CallerIdentity.ofSession(accessor.getSessionAttributes());
    }

    private static void refuseIfDenied(Optional<AccessDenial> denial) {
        if (denial.isPresent()) {
            throw new MessageDeliveryException(denial.get().message());
        }
    }
}
