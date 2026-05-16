package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.config.JwtClaimUtils;
import self.research.ontology.owlEditor.websocket.WebSocketEventListener;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Admin-only endpoint: returns currently connected WebSocket sessions.
 * Use this to check if it's safe to run migrations or restarts.
 */
@RestController
@RequestMapping("/api/ontology/admin")
public class ActiveConnectionsController {

    private final WebSocketEventListener wsListener;

    public ActiveConnectionsController(WebSocketEventListener wsListener) {
        this.wsListener = wsListener;
    }

    /**
     * GET /api/ontology/admin/active-connections
     * Returns all live WebSocket sessions grouped by unique user.
     */
    @GetMapping("/active-connections")
    public ResponseEntity<?> getActiveConnections(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        if (!JwtClaimUtils.extractIsAdmin(authHeader)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }

        List<Map<String, Object>> sessions = wsListener.getAllActiveSessions();
        int totalConnections = wsListener.getTotalConnectionCount();

        // Count unique users (a user can have multiple tabs open)
        long uniqueUsers = sessions.stream()
                .map(s -> (String) s.get("userId"))
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .count();

        // Deduplicate by userId for the summary view (keep latest activity)
        List<Map<String, Object>> userSummary = sessions.stream()
                .collect(Collectors.toMap(
                        s -> (String) s.get("userId"),
                        s -> s,
                        (a, b) -> ((Long) a.get("lastActivity")) >= ((Long) b.get("lastActivity")) ? a : b
                ))
                .values()
                .stream()
                .sorted((a, b) -> Long.compare((Long) b.get("lastActivity"), (Long) a.get("lastActivity")))
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "totalConnections", totalConnections,
                "uniqueUsers", uniqueUsers,
                "safeToMigrate", totalConnections == 0,
                "users", userSummary
        ));
    }
}
