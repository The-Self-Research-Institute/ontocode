package self.research.ontology.owlEditor.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import self.research.ontology.owlEditor.service.ProjectAccessService;
import self.research.ontology.owlEditor.service.ProjectAuthorization;

import javax.crypto.SecretKey;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSocketAuthChannelInterceptorTest {

    private final SecretKey key = Jwts.SIG.HS256.key().build();
    private final MessageChannel channel = mock(MessageChannel.class);
    private ProjectAccessService access;
    private WebSocketAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        access = mock(ProjectAccessService.class);
        interceptor = interceptorRequiring(true);
    }

    private WebSocketAuthChannelInterceptor interceptorRequiring(boolean requireJwt) {
        return new WebSocketAuthChannelInterceptor(new JwtVerifier(Encoders.BASE64.encode(key.getEncoded())),
                new ProjectAuthorization(access, false), requireJwt);
    }

    private String token(String email, String userId) {
        return "Bearer " + Jwts.builder().subject(email).claim("userId", userId).claim("plan", "PRO").signWith(key).compact();
    }

    private static Map<String, Object> session(String userId, String email) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", userId);
        attrs.put("email", email);
        return attrs;
    }

    private Message<?> message(StompCommand command, String destination, String authorization, Map<String, Object> attrs) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        accessor.setSessionAttributes(attrs);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void aValidTokenOnConnectPutsTheVerifiedIdentityInTheSession() {
        Map<String, Object> attrs = new HashMap<>();

        interceptor.preSend(message(StompCommand.CONNECT, null, token("owner@x.org", "u-1"), attrs), channel);

        assertEquals("u-1", attrs.get("userId"));
        assertEquals("owner@x.org", attrs.get("email"));
        assertEquals("PRO", attrs.get("plan"));
    }

    @Test
    void aTokenWithABadSignatureIsRefusedEvenWhenTokensAreNotRequired() {
        String forged = "Bearer " + Jwts.builder().subject("owner@x.org").signWith(Jwts.SIG.HS256.key().build()).compact();

        assertThrows(MessageDeliveryException.class, () -> interceptorRequiring(false)
                .preSend(message(StompCommand.CONNECT, null, forged, new HashMap<>()), channel));
    }

    @Test
    void connectingWithoutATokenIsRefusedOnlyWhenTokensAreRequired() {
        Message<?> anonymous = message(StompCommand.CONNECT, null, null, new HashMap<>());

        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(anonymous, channel));
        assertDoesNotThrow(() -> interceptorRequiring(false).preSend(anonymous, channel));
    }

    @Test
    void subscribingToAProjectNeedsAccessToIt() {
        when(access.hasProjectAccess("p1", "u-1", "owner@x.org")).thenReturn(true);

        assertDoesNotThrow(() -> interceptor.preSend(message(StompCommand.SUBSCRIBE, "/topic/ontology/p1", null,
                session("u-1", "owner@x.org")), channel));
        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(message(StompCommand.SUBSCRIBE,
                "/topic/ontology/p1", null, session("u-9", "mallory@x.org")), channel));
    }

    @Test
    void everyProjectTopicIsProtected() {
        for (String topic : new String[]{"/topic/locks/p1", "/topic/presence/p1", "/topic/graph/p1", "/topic/import/p1",
                "/topic/queue/p1"}) {
            assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(message(StompCommand.SUBSCRIBE, topic,
                    null, session("u-9", "mallory@x.org")), channel), topic);
        }
    }

    @Test
    void theSharedNotificationsOfAnotherUserCannotBeSubscribedTo() {
        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(message(StompCommand.SUBSCRIBE,
                "/topic/shares/victim@x.org", null, session("u-9", "mallory@x.org")), channel));
        assertDoesNotThrow(() -> interceptor.preSend(message(StompCommand.SUBSCRIBE, "/topic/shares/Mallory@x.org",
                null, session("u-9", "mallory@x.org")), channel));
    }

    @Test
    void topicsThatAreNotProjectScopedNeedOnlyASignedInUser() {
        assertDoesNotThrow(() -> interceptor.preSend(message(StompCommand.SUBSCRIBE, "/topic/queue/stats", null,
                session("u-1", "owner@x.org")), channel));
        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(message(StompCommand.SUBSCRIBE,
                "/topic/queue/stats", null, new HashMap<>()), channel));
    }

    @Test
    void aClientCannotPublishStraightToATopic() {
        assertThrows(MessageDeliveryException.class, () -> interceptorRequiring(false).preSend(message(StompCommand.SEND,
                "/topic/ontology/p1", null, session("u-1", "owner@x.org")), channel));
    }

    @Test
    void sendingAnEditToAProjectNeedsAccessToIt() {
        when(access.hasProjectAccess("p1", "u-1", "owner@x.org")).thenReturn(true);

        assertDoesNotThrow(() -> interceptor.preSend(message(StompCommand.SEND, "/app/collab/p1/edit", null,
                session("u-1", "owner@x.org")), channel));
        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(message(StompCommand.SEND,
                "/app/collab/p1/edit", null, session("u-9", "mallory@x.org")), channel));
    }
}
