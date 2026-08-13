package self.research.ontology.owlEditor.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;

@Slf4j
@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String[] parts = authHeader.substring(7).split("\\.");
                if (parts.length == 3) {
                    try {
                        byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
                        JsonNode claims = MAPPER.readTree(decoded);
                        String plan = claims.has("plan") ? claims.get("plan").asText() : "FREE";
                        String userId = claims.has("userId") ? claims.get("userId").asText() : null;
                        String username = claims.has("sub") ? claims.get("sub").asText() : "anonymous";

                        Map<String, Object> attrs = accessor.getSessionAttributes();
                        if (attrs != null) {
                            attrs.put("plan", plan);
                            attrs.put("userId", userId);
                            attrs.put("username", username);
                        }
                        log.debug("[WS-Auth] Session {} — plan={} userId={}", accessor.getSessionId(), plan, userId);
                    } catch (Exception e) {
                        log.debug("[WS-Auth] Could not decode JWT from CONNECT: {}", e.getMessage());
                    }
                }
            }
        }

        return message;
    }
}
