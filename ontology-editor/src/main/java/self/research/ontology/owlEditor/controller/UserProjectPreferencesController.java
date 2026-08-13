package self.research.ontology.owlEditor.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.config.JwtClaimUtils;
import self.research.ontology.owlEditor.document.UserProjectPreferences;
import self.research.ontology.owlEditor.repository.UserProjectPreferencesRepository;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/preferences")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class UserProjectPreferencesController {

    private static final Set<String> VALID_SYNC_MODES = Set.of("public", "private");

    private final UserProjectPreferencesRepository preferencesRepository;
    private final MongoTemplate mongoTemplate;

    @GetMapping("/{projectId}")
    public ResponseEntity<Map<String, Object>> getPreferences(
            @PathVariable String projectId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        String email = JwtClaimUtils.extractEmail(authHeader);
        if (email == null || email.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        return preferencesRepository.findByUserEmailAndProjectId(email, projectId)
                .map(p -> ResponseEntity.ok(Map.<String, Object>of("syncMode", p.getSyncMode())))
                .orElse(ResponseEntity.ok(Map.of("syncMode", "public")));
    }

    @PutMapping("/{projectId}")
    public ResponseEntity<Map<String, Object>> savePreferences(
            @PathVariable String projectId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, String> body) {

        String email = JwtClaimUtils.extractEmail(authHeader);
        if (email == null || email.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String syncMode = body.get("syncMode");
        if (syncMode == null || !VALID_SYNC_MODES.contains(syncMode)) {
            return ResponseEntity.badRequest().body(Map.of("error", "syncMode must be 'public' or 'private'"));
        }

        Query query = Query.query(Criteria.where("userEmail").is(email).and("projectId").is(projectId));
        Update update = new Update()
                .set("syncMode", syncMode)
                .set("updatedAt", Instant.now())
                .setOnInsert("userEmail", email)
                .setOnInsert("projectId", projectId);
        mongoTemplate.upsert(query, update, UserProjectPreferences.class);

        log.debug("[Preferences] {} set syncMode={} for project {}", email, syncMode, projectId);
        return ResponseEntity.ok(Map.of("syncMode", syncMode));
    }
}
