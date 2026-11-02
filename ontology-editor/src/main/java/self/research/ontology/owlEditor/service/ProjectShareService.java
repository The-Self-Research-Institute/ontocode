package self.research.ontology.owlEditor.service;

import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;
import self.research.ontology.owlEditor.document.ProjectShare;
import self.research.ontology.owlEditor.repository.ProjectShareRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectShareService {
    
    private final ProjectShareRepository shareRepository;
    private final RestTemplate restTemplate;
    private final EmailNotificationService emailNotificationService;
    private final ProjectMetadataService metadataService;
    private final SimpMessagingTemplate messagingTemplate;
    
    @Value("${auth.service.url:http://localhost:8086}")
    private String authServiceUrl;

    @Value("${ontocode.internal.token:ontocode-internal}")
    private String internalToken;

    @Autowired(required = false)
    private MongoOperations mongo;

    private static final String SHARE_QUEUE = "/queue/shares";

    private List<String> notificationRecipients(String email) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        List<String> recipients = new ArrayList<>();
        findUserId(email.trim(), normalized).ifPresent(recipients::add);
        recipients.add(normalized);
        return recipients;
    }

    private Optional<String> findUserId(String email, String normalized) {
        if (mongo == null) {
            return Optional.empty();
        }
        Document user = mongo.findOne(Query.query(Criteria.where("email").in(email, normalized)), Document.class, "users");
        Object id = user == null ? null : user.get("_id");
        return id == null ? Optional.empty() : Optional.of(id.toString());
    }
    
    /**
     * Get user email from auth service by username
     */
    private String getEmailFromUsername(String username) {
        try {
            java.net.URI url = org.springframework.web.util.UriComponentsBuilder.fromUriString(authServiceUrl)
                    .path("/api/internal/users/email").queryParam("username", username).build().encode().toUri();
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("X-Ontocode-Internal-Token", internalToken);
            @SuppressWarnings("unchecked")
            Map<String, String> response = restTemplate.exchange(url, org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(headers), Map.class).getBody();
            if (response != null && response.containsKey("email")) {
                return response.get("email");
            }
            log.warn("No email found for username: {}", username);
            return username; // Fallback to username if not found
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("User not found in auth service: {}", username);
            return username; // Fallback to username
        } catch (Exception e) {
            log.error("Failed to fetch email from auth service for username: {}", username, e);
            return username; // Fallback to username
        }
    }
    
    public ProjectShare createShare(String projectId, String ownerIdentifier, String permission) {
        // Check if share already exists
        Optional<ProjectShare> existing = shareRepository.findByProjectId(projectId);
        if (existing.isPresent()) {
            return existing.get();
        }
        
        // If ownerIdentifier looks like an email, use it directly, otherwise fetch from auth service
        String ownerEmail;
        if (ownerIdentifier.contains("@")) {
            ownerEmail = ownerIdentifier;
        } else {
            ownerEmail = getEmailFromUsername(ownerIdentifier);
        }
        
        ProjectShare share = new ProjectShare(projectId, ownerEmail, permission);
        return shareRepository.save(share);
    }
    
    public Optional<ProjectShare> getShareByProjectId(String projectId) {
        return shareRepository.findByProjectId(projectId);
    }
    
    public Optional<ProjectShare> getShareByLink(String shareLink) {
        return shareRepository.findByShareLink(shareLink);
    }
    
    public ProjectShare addEmailAccess(String projectId, String email, String permission) {
        ProjectShare share = shareRepository.findByProjectId(projectId)
                .orElseThrow(() -> new RuntimeException("Share not found for project: " + projectId));
        
        share.addSharedEmail(email);
        // Update the default permission if provided
        if (permission != null && !permission.isEmpty()) {
            share.setPermission(permission);
        }
        ProjectShare savedShare = shareRepository.save(share);
        
        // Get file name and owner info
        String fileName = metadataService.readStatus(projectId)
                .map(status -> status.filename())
                .orElse("Untitled File");
        String fromUsername = share.getOwnerEmail().split("@")[0];
        String fromEmail = share.getOwnerEmail();
        
        // Send email notification (async)
        try {
            emailNotificationService.sendShareNotification(
                email, 
                fromUsername, 
                fileName, 
                permission != null ? permission : "view"
            );
        } catch (Exception e) {
            log.error("Failed to send share notification email, but share was created", e);
            // Don't throw - email failure shouldn't break sharing
        }
        
        // Send WebSocket notification (instant)
        try {
            self.research.ontology.owlEditor.model.collaboration.ShareNotification notification = 
                self.research.ontology.owlEditor.model.collaboration.ShareNotification.builder()
                    .projectId(projectId)
                    .fileName(fileName)
                    .sharedByUsername(fromUsername)
                    .sharedByEmail(fromEmail)
                    .sharedWithEmail(email)
                    .permission(permission != null ? permission : "view")
                    .message(String.format("%s shared '%s' with you (%s access)", 
                        fromUsername, fileName, permission != null ? permission : "view"))
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            for (String recipient : notificationRecipients(email)) {
                messagingTemplate.convertAndSendToUser(recipient, SHARE_QUEUE, notification);
            }
            log.info("Sent real-time share notification for project {}", projectId);
        } catch (Exception e) {
            log.error("Failed to send WebSocket share notification", e);
            // Don't throw - notification failure shouldn't break sharing
        }
        
        return savedShare;
    }
    
    // Keep backward compatibility
    public ProjectShare addEmailAccess(String projectId, String email) {
        return addEmailAccess(projectId, email, "view");
    }
    
    public ProjectShare removeEmailAccess(String projectId, String email) {
        ProjectShare share = shareRepository.findByProjectId(projectId)
                .orElseThrow(() -> new RuntimeException("Share not found for project: " + projectId));
        
        share.removeSharedEmail(email);
        return shareRepository.save(share);
    }
    
    public List<ProjectShare> getMyShares(String ownerEmail) {
        return shareRepository.findByOwnerEmail(ownerEmail);
    }
    
    public List<ProjectShare> getSharedWithMe(String email) {
        return shareRepository.findBySharedWithEmailsContaining(email);
    }
    
    public boolean isFilenameInSharedFiles(String filename, String userEmail) {
        if (filename == null || userEmail == null) {
            return false;
        }
        List<ProjectShare> sharedWithMe = getSharedWithMe(userEmail);
        List<String> sharedProjectIds = sharedWithMe.stream()
                .map(ProjectShare::getProjectId)
                .toList();
        
        // Check if any shared project has this filename
        return sharedProjectIds.stream()
                .map(projectId -> metadataService.readStatus(projectId))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .anyMatch(status -> filename.equals(status.filename()));
    }
    
    public void deleteShare(String projectId) {
        shareRepository.findByProjectId(projectId)
                .ifPresent(shareRepository::delete);
    }
    
    public boolean hasAccess(String projectId, String userEmail) {
        return shareRepository.findByProjectId(projectId)
                .map(share -> share.getOwnerEmail().equals(userEmail) 
                           || share.getSharedWithEmails().contains(userEmail))
                .orElse(false);
    }
}
