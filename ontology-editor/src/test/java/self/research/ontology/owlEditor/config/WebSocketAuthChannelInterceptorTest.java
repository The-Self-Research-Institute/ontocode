package self.research.ontology.owlEditor.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.repository.AssistantEditGroupRepository;
import self.research.ontology.owlEditor.service.JobAccess;
import self.research.ontology.owlEditor.service.JobOwnerRegistry;
import self.research.ontology.owlEditor.service.ProjectAccessService;
import self.research.ontology.owlEditor.service.ProjectAuthorization;
import self.research.ontology.owlEditor.service.RuleRunAccess;
import self.research.ontology.owlEditor.service.RuleRunStore;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSocketAuthChannelInterceptorTest {

    private final SecretKey key = Jwts.SIG.HS256.key().build();
    private ProjectAccessService projects;
    private JobOwnerRegistry registry;
    private RuleRunStore runs;
    private AssistantEditGroupRepository groups;
    private WebSocketAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectAccessService.class);
        when(projects.hasProjectAccess(anyString(), anyString())).thenReturn(false);
        when(projects.hasProjectAccess(eq("p1"), eq("ann@x.org"))).thenReturn(true);
        when(projects.hasProjectAccess(eq("p1"), eq("bob@x.org"))).thenReturn(true);
        when(projects.hasProjectAccess(eq("p1"), eq("u-ann"), eq("ann@x.org"))).thenReturn(true);
        when(projects.hasProjectAccess(eq("p1"), eq("u-bob"), eq("bob@x.org"))).thenReturn(true);
        registry = new JobOwnerRegistry();
        runs = new RuleRunStore(Clock.systemUTC(), Duration.ofMinutes(15), 5, 50, 1000, 1000);
        groups = mock(AssistantEditGroupRepository.class);
        JobAccess jobAccess = new JobAccess(new RuleRunAccess(projects, false), registry, projects);
        interceptor = new WebSocketAuthChannelInterceptor(new JwtVerifier(Encoders.BASE64.encode(key.getEncoded())),
                new ProjectAuthorization(projects, false), jobAccess, projects, runs, groups);
        ReflectionTestUtils.setField(interceptor, "requireJwt", true);
        ReflectionTestUtils.setField(interceptor, "desktopMode", false);
    }

    private String token(String email, String userId) {
        return Jwts.builder().subject(email).claim("userId", userId).claim("plan", "PRO")
                .expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(key).compact();
    }

    private Message<byte[]> connectMessage(Map<String, Object> attrs, String authHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("s1");
        accessor.setSessionAttributes(attrs);
        if (authHeader != null) {
            accessor.addNativeHeader("Authorization", authHeader);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> connect(Map<String, Object> attrs, String authHeader) {
        return interceptor.preSend(connectMessage(attrs, authHeader), null);
    }

    private Map<String, Object> connected(String email, String userId) {
        Map<String, Object> attrs = new HashMap<>();
        assertNotNull(connect(attrs, "Bearer " + token(email, userId)));
        return attrs;
    }

    private boolean subscribe(Map<String, Object> attrs, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("s1");
        accessor.setSessionAttributes(attrs);
        accessor.setDestination(destination);
        accessor.setSubscriptionId("sub-1");
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return interceptor.preSend(message, null) != null;
    }

    @Test
    void validTokenSetsPrincipalAndSessionAttributes() {
        Map<String, Object> attrs = new HashMap<>();

        Message<?> result = connect(attrs, "Bearer " + token("ann@x.org", "u-ann"));

        assertEquals("u-ann", StompHeaderAccessor.wrap(result).getUser().getName());
        assertEquals("PRO", attrs.get("plan"));
        assertEquals("u-ann", attrs.get("userId"));
        assertEquals("ann@x.org", attrs.get("username"));
        assertEquals("ann@x.org", attrs.get("email"));
    }

    @Test
    void principalFallsBackToTheSubjectWhenThereIsNoUserIdClaim() {
        Map<String, Object> attrs = new HashMap<>();
        String noUserId = Jwts.builder().subject("ann@x.org").signWith(key).compact();

        Message<?> result = connect(attrs, "Bearer " + noUserId);

        assertEquals("ann@x.org", StompHeaderAccessor.wrap(result).getUser().getName());
        assertEquals("FREE", attrs.get("plan"));
    }

    @Test
    void tokenWithoutUserIdConnectsOnAMapThatRejectsNulls() {
        Map<String, Object> attrs = new java.util.concurrent.ConcurrentHashMap<>();
        String noUserId = Jwts.builder().subject("ann@x.org").signWith(key).compact();

        assertNotNull(connect(attrs, "Bearer " + noUserId));

        assertTrue(!attrs.containsKey("userId"));
        assertEquals("ann@x.org", attrs.get("email"));
    }

    @Test
    void expiredTokenIsRefusedWhenJwtIsRequired() {
        String expired = Jwts.builder().subject("ann@x.org").claim("userId", "u-ann")
                .expiration(new Date(System.currentTimeMillis() - 60_000)).signWith(key).compact();

        assertThrows(MessageDeliveryException.class, () -> connect(new HashMap<>(), "Bearer " + expired));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRefusedWhenJwtIsRequired() {
        String forged = Jwts.builder().subject("ann@x.org").claim("userId", "u-ann")
                .signWith(Jwts.SIG.HS256.key().build()).compact();

        assertThrows(MessageDeliveryException.class, () -> connect(new HashMap<>(), "Bearer " + forged));
    }

    @Test
    void unsignedAlgNoneTokenIsRefusedWhenJwtIsRequired() {
        String unsigned = Jwts.builder().subject("ann@x.org").claim("userId", "u-ann").compact();

        assertThrows(MessageDeliveryException.class, () -> connect(new HashMap<>(), "Bearer " + unsigned));
    }

    @Test
    void tokenWithoutASubjectIsRefusedWhenJwtIsRequired() {
        String noSubject = Jwts.builder().claim("userId", "u-ann").signWith(key).compact();

        assertThrows(MessageDeliveryException.class, () -> connect(new HashMap<>(), "Bearer " + noSubject));
    }

    @Test
    void missingTokenIsRefusedWhenJwtIsRequired() {
        assertThrows(MessageDeliveryException.class, () -> connect(new HashMap<>(), null));
    }

    @Test
    void missingTokenIsAllowedWhenJwtIsNotRequired() {
        ReflectionTestUtils.setField(interceptor, "requireJwt", false);
        Map<String, Object> attrs = new HashMap<>();

        Message<?> result = connect(attrs, null);

        assertNotNull(result);
        assertNull(StompHeaderAccessor.wrap(result).getUser());
        assertTrue(attrs.isEmpty());
    }

    @Test
    void forgedTokenIsRefusedEvenWhenJwtIsNotRequired() {
        ReflectionTestUtils.setField(interceptor, "requireJwt", false);
        Map<String, Object> attrs = new HashMap<>();
        String forged = Jwts.builder().subject("ann@x.org").claim("userId", "u-ann")
                .signWith(Jwts.SIG.HS256.key().build()).compact();

        assertThrows(MessageDeliveryException.class, () -> connect(attrs, "Bearer " + forged));
        assertTrue(attrs.isEmpty());
    }

    @Test
    void ownRunCanBeFollowedAndAnotherUsersRunCannot() {
        String runId = runs.put("p1", "u-ann", false, List.of(), List.of());

        assertTrue(subscribe(connected("ann@x.org", "u-ann"), "/topic/run/" + runId));
        assertTrue(!subscribe(connected("bob@x.org", "u-bob"), "/topic/run/" + runId));
        assertTrue(!subscribe(connected("ann@x.org", "u-ann"), "/topic/run/unknown-run"));
    }

    @Test
    void jobTopicsFollowTheJobOwnerAndProjectAccess() {
        registry.register("job-1", "p1", "u-ann");
        for (String kind : List.of("reasoning", "dlquery")) {
            assertTrue(subscribe(connected("ann@x.org", "u-ann"), "/topic/" + kind + "/job-1"), kind + " owner");
            assertTrue(!subscribe(connected("bob@x.org", "u-bob"), "/topic/" + kind + "/job-1"), kind + " other member");
            assertTrue(!subscribe(connected("eve@x.org", "u-eve"), "/topic/" + kind + "/job-1"), kind + " non member");
            assertTrue(!subscribe(connected("ann@x.org", "u-ann"), "/topic/" + kind + "/never-seen"), kind + " unknown");
        }
    }

    @Test
    void projectTopicsNeedProjectAccess() {
        for (String kind : List.of("ontology", "presence", "locks", "graph", "import", "queue")) {
            assertTrue(subscribe(connected("ann@x.org", "u-ann"), "/topic/" + kind + "/p1"), kind + " member");
            assertTrue(!subscribe(connected("eve@x.org", "u-eve"), "/topic/" + kind + "/p1"), kind + " non member");
        }
    }

    @Test
    void nonexistentProjectTopicFollowsTheRestRule() {
        when(projects.projectExists("ghost")).thenReturn(false);

        assertTrue(!subscribe(connected("ann@x.org", "u-ann"), "/topic/import/ghost"));
        ReflectionTestUtils.setField(interceptor, "requireJwt", false);
        assertTrue(subscribe(connected("ann@x.org", "u-ann"), "/topic/import/ghost"));
    }

    @Test
    void globalQueueStatsAreOpenToSignedInUsers() {
        Map<String, Object> ann = connected("ann@x.org", "u-ann");

        assertTrue(subscribe(ann, "/topic/queue/stats"));
        assertTrue(subscribe(ann, "/topic/dlquery/stats"));
    }

    @Test
    void userQueuesAreOnlyTheCallersOwn() {
        Map<String, Object> ann = connected("ann@x.org", "u-ann");

        assertTrue(subscribe(ann, "/user/queue/errors"));
        assertTrue(subscribe(ann, "/user/queue/shares"));
        assertTrue(!subscribe(ann, "/user/u-bob/queue/errors"));
        assertTrue(!subscribe(ann, "/queue/errors-userabc123"));
    }

    @Test
    void shareTopicIsOnlyForTheOwnAddress() {
        Map<String, Object> ann = connected("ann@x.org", "u-ann");

        assertTrue(subscribe(ann, "/topic/shares/ann@x.org"));
        assertTrue(subscribe(ann, "/topic/shares/ANN@x.org"));
        assertTrue(!subscribe(ann, "/topic/shares/bob@x.org"));
    }

    @Test
    void assistantConsistencyTopicBelongsToTheGroupOwner() {
        when(groups.findById("g1")).thenReturn(Optional.of(
                AssistantEditGroupDocument.builder().id("g1").projectId("p1").userEmail("ann@x.org").build()));
        when(groups.findById("missing")).thenReturn(Optional.empty());

        assertTrue(subscribe(connected("ann@x.org", "u-ann"), "/topic/assistant/consistency-check/g1"));
        assertTrue(!subscribe(connected("bob@x.org", "u-bob"), "/topic/assistant/consistency-check/g1"));
        assertTrue(!subscribe(connected("ann@x.org", "u-ann"), "/topic/assistant/consistency-check/missing"));
        assertTrue(!subscribe(connected("ann@x.org", "u-ann"), "/topic/assistant/other/g1"));
    }

    @Test
    void unknownDestinationIsDeniedOnlyWhenJwtIsRequired() {
        Map<String, Object> ann = connected("ann@x.org", "u-ann");

        assertTrue(!subscribe(ann, "/topic/mystery/p1"));
        assertTrue(!subscribe(ann, "/elsewhere/x"));
        ReflectionTestUtils.setField(interceptor, "requireJwt", false);
        assertTrue(subscribe(ann, "/topic/mystery/p1"));
    }

    @Test
    void anonymousSessionCanSubscribeOnlyWhenJwtIsNotRequired() {
        ReflectionTestUtils.setField(interceptor, "requireJwt", false);
        Map<String, Object> anonymous = new HashMap<>();
        assertNotNull(connect(anonymous, null));

        assertTrue(subscribe(anonymous, "/topic/ontology/p1"));
        ReflectionTestUtils.setField(interceptor, "requireJwt", true);
        assertTrue(!subscribe(anonymous, "/topic/ontology/p1"));
    }

    @Test
    void desktopModeKeepsTheOpenBehaviour() {
        ReflectionTestUtils.setField(interceptor, "desktopMode", true);
        Map<String, Object> attrs = new HashMap<>();

        assertNotNull(connect(attrs, null));
        assertTrue(subscribe(attrs, "/topic/ontology/p1"));
    }

    private Message<?> send(Map<String, Object> attrs, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination(destination);
        accessor.setSessionAttributes(attrs);
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        Message<?> result = interceptor.preSend(message, null);
        assertEquals(message, result);
        return result;
    }

    @Test
    void sendFramesOfAProjectMemberAreNotTouched() {
        send(connected("ann@x.org", "u-ann"), "/app/collab/p1/edit");
    }

    @Test
    void sendingAnEditToAProjectNeedsAccessToIt() {
        assertThrows(MessageDeliveryException.class, () -> send(connected("eve@x.org", "u-eve"), "/app/collab/p1/edit"));
        assertThrows(MessageDeliveryException.class, () -> send(new HashMap<>(), "/app/collab/p1/edit"));
    }

    @Test
    void aClientCannotPublishStraightToATopic() {
        ReflectionTestUtils.setField(interceptor, "requireJwt", false);

        assertThrows(MessageDeliveryException.class, () -> send(connected("ann@x.org", "u-ann"), "/topic/ontology/p1"));
    }

    @Test
    void subscriptionPatternsThatCouldMatchOtherProjectsAreRefused() {
        Map<String, Object> ann = connected("ann@x.org", "u-ann");

        for (String topic : List.of("/topic/**", "/topic/*/p1", "/topic/ontology/*", "/topic/ontology/{id}", "/topic/?")) {
            assertTrue(!subscribe(ann, topic), topic);
        }
    }

    @Test
    void sessionAttributesNotSetByAVerifiedConnectDoNotCountAsSignedIn() {
        Map<String, Object> claimed = new HashMap<>();
        claimed.put("userId", "u-ann");
        claimed.put("email", "ann@x.org");

        assertTrue(!subscribe(claimed, "/topic/ontology/p1"));
    }
}
