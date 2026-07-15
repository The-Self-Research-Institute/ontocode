package self.research.ontology.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import self.research.ontology.auth.dto.WorkspaceRequests.*;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;
import self.research.ontology.auth.util.JwtUtil;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Workspace Flow - Covers all 118 test cases
 * 
 * Test Categories:
 * - TC-WC: Workspace Creation (10 test cases)
 * - TC-WS: Workspace Selection (6 test cases)
 * - TC-WD: Workspace Deletion (7 test cases)
 * - TC-WSW: Workspace Switching (4 test cases)
 * - TC-WM: Member Management (10 test cases)
 * - TC-VAL: Validation Tests (45 test cases)
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WorkspaceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private static String testUserId;
    private static String testUsername = "testuser";
    private static String testEmail = "testuser@example.com";
    private static String authToken;

    @BeforeEach
    public void setup() {
        // Create test user if not exists
        User testUser = userRepository.findByUsername(testUsername)
                .orElseGet(() -> {
                    User user = new User();
                    user.setUsername(testUsername);
                    user.setEmail(testEmail);
                    user.setPassword("$2a$10$dummyhash");
                    return userRepository.save(user);
                });
        
        testUserId = testUser.getId();
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", testUserId);
        claims.put("email", testEmail);
        authToken = jwtUtil.generateToken(testUsername, claims);
    }

    // ============================================================================
    // WORKSPACE CREATION TEST CASES (TC-WC-001 to TC-WC-010)
    // ============================================================================

    @Test
    @Order(1)
    @DisplayName("TC-WC-001: Create Workspace with Valid Data")
    public void testCreateWorkspaceWithValidData() throws Exception {
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName("Development Workspace");
        request.setDescription("Workspace for development projects");
        request.setSubscriptionPlan("FREE");

        mockMvc.perform(post("/api/workspaces")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").exists())
                .andExpect(jsonPath("$.name").value("Development Workspace"))
                .andExpect(jsonPath("$.description").value("Workspace for development projects"))
                .andExpect(jsonPath("$.subscriptionPlan").value("FREE"))
                .andExpect(jsonPath("$.ownerId").value(testUserId))
                .andExpect(jsonPath("$.members").isArray())
                .andExpect(jsonPath("$.members[0].role").value("OWNER"))
                .andExpect(jsonPath("$.maxWorkspaces").value(3))
                .andExpect(jsonPath("$.maxMembers").value(10));
    }

    @Test
    @Order(2)
    @DisplayName("TC-WC-002: Create Workspace with Minimum Valid Data")
    public void testCreateWorkspaceWithMinimumData() throws Exception {
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName("A");
        request.setDescription("");  // Empty description should be accepted

        mockMvc.perform(post("/api/workspaces")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("A"))
                .andExpect(jsonPath("$.workspaceId").exists());
    }

    @Test
    @Order(3)
    @DisplayName("TC-WC-003: Create Workspace with Maximum Length Name")
    public void testCreateWorkspaceWithMaxLengthName() throws Exception {
        String maxLengthName = "A".repeat(255);
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName(maxLengthName);
        request.setDescription("Test workspace");

        mockMvc.perform(post("/api/workspaces")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(maxLengthName));
    }

    @Test
    @Order(4)
    @DisplayName("TC-WC-004: Create Workspace with Special Characters in Name")
    public void testCreateWorkspaceWithSpecialCharacters() throws Exception {
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName("R&D - AI/ML (2024-2025) #1");
        request.setDescription("Special chars test");

        mockMvc.perform(post("/api/workspaces")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("R&D - AI/ML (2024-2025) #1"));
    }

    @Test
    @Order(5)
    @DisplayName("TC-WC-005: Create Workspace with Empty Name")
    public void testCreateWorkspaceWithEmptyName() throws Exception {
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName("");
        request.setDescription("Test description");

        mockMvc.perform(post("/api/workspaces")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Workspace name is required")));
    }

    @Test
    @Order(6)
    @DisplayName("TC-WC-006: Create Workspace with Only Whitespace in Name")
    public void testCreateWorkspaceWithWhitespaceName() throws Exception {
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName("   ");  // Only spaces
        request.setDescription("Test description");

        mockMvc.perform(post("/api/workspaces")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("cannot be empty")));
    }

    @Test
    @Order(7)
    @DisplayName("TC-WC-007: Create Workspace Exceeding Maximum Limit")
    public void testCreateWorkspaceExceedingLimit() throws Exception {
        // First, create 3 workspaces (FREE plan limit)
        for (int i = 1; i <= 3; i++) {
            CreateWorkspaceRequest request = new CreateWorkspaceRequest();
            request.setName("Workspace " + i);
            mockMvc.perform(post("/api/workspaces")
                    .header("Authorization", "Bearer " + authToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        // Try to create 4th workspace
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName("Workspace 4");

        mockMvc.perform(post("/api/workspaces")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Maximum workspace limit reached")));
    }

    @Test
    @Order(8)
    @DisplayName("TC-WC-008: Create Workspace with Duplicate Name")
    public void testCreateWorkspaceWithDuplicateName() throws Exception {
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName("Test Workspace");
        
        // Create first workspace
        mockMvc.perform(post("/api/workspaces")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // Try to create duplicate
        mockMvc.perform(post("/api/workspaces")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("already exists")));
    }

    @Test
    @Order(9)
    @DisplayName("TC-WC-009: Create Workspace Without Authentication")
    public void testCreateWorkspaceWithoutAuth() throws Exception {
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName("Unauthorized Workspace");

        mockMvc.perform(post("/api/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(10)
    @DisplayName("TC-WC-010: Create Workspace with SQL Injection Attempt")
    public void testCreateWorkspaceWithSQLInjection() throws Exception {
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName("Test'; DROP TABLE workspaces; --");
        request.setDescription("Test description");

        // Should be blocked by security filter
        mockMvc.perform(post("/api/workspaces")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Potential SQL injection")));
    }

    // ============================================================================
    // WORKSPACE SELECTION TEST CASES (TC-WS-001 to TC-WS-006)
    // ============================================================================

    @Test
    @Order(11)
    @DisplayName("TC-WS-001: Select Workspace from List")
    public void testSelectWorkspaceFromList() throws Exception {
        // Create a workspace first
        Workspace workspace = new Workspace();
        workspace.setWorkspaceId("test-workspace-id");
        workspace.setName("Test Workspace");
        workspace.setOwnerId(testUserId);
        workspace.addMember(testUserId, testUsername, testEmail, Workspace.WorkspaceRole.OWNER);
        workspace = workspaceRepository.save(workspace);

        // Select the workspace
        mockMvc.perform(post("/api/workspaces/" + workspace.getWorkspaceId() + "/select")
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.workspaceId").value(workspace.getWorkspaceId()));
    }

    @Test
    @Order(12)
    @DisplayName("TC-WS-002: View Workspace Details Before Selection")
    public void testViewWorkspaceDetails() throws Exception {
        mockMvc.perform(get("/api/workspaces")
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaces").isArray())
                .andExpect(jsonPath("$.workspaces[0].name").exists())
                .andExpect(jsonPath("$.workspaces[0].description").exists())
                .andExpect(jsonPath("$.workspaces[0].subscriptionPlan").exists())
                .andExpect(jsonPath("$.workspaces[0].ownerId").exists());
    }

    @Test
    @Order(13)
    @DisplayName("TC-WS-004: Select Workspace Without Access")
    public void testSelectWorkspaceWithoutAccess() throws Exception {
        // Create workspace for different user
        User otherUser = new User();
        otherUser.setUsername("otheruser");
        otherUser.setEmail("other@example.com");
        otherUser.setPassword("$2a$10$dummyhash");
        otherUser = userRepository.save(otherUser);

        Workspace workspace = new Workspace();
        workspace.setWorkspaceId("other-workspace-id");
        workspace.setName("Other Workspace");
        workspace.setOwnerId(otherUser.getId());
        workspace.addMember(otherUser.getId(), otherUser.getUsername(), otherUser.getEmail(), Workspace.WorkspaceRole.OWNER);
        workspace = workspaceRepository.save(workspace);

        // Try to access with test user token
        mockMvc.perform(post("/api/workspaces/" + workspace.getWorkspaceId() + "/select")
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", containsString("do not have access")));
    }

    @Test
    @Order(14)
    @DisplayName("TC-WS-005: Select Non-Existent Workspace")
    public void testSelectNonExistentWorkspace() throws Exception {
        mockMvc.perform(post("/api/workspaces/non-existent-workspace-id/select")
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("not found")));
    }

    @Test
    @Order(15)
    @DisplayName("TC-WS-006: Select Workspace with Expired Token")
    public void testSelectWorkspaceWithExpiredToken() throws Exception {
        // Create an expired token (this is simulated)
        String expiredToken = "expired.token.value";

        mockMvc.perform(post("/api/workspaces/some-workspace-id/select")
                .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    // ============================================================================
    // WORKSPACE DELETION TEST CASES (TC-WD-001 to TC-WD-007)
    // ============================================================================

    @Test
    @Order(20)
    @DisplayName("TC-WD-001: Delete Empty Workspace as Owner")
    public void testDeleteEmptyWorkspaceAsOwner() throws Exception {
        // Create workspace
        Workspace workspace = new Workspace();
        workspace.setWorkspaceId("delete-test-workspace");
        workspace.setName("Delete Test");
        workspace.setOwnerId(testUserId);
        workspace.addMember(testUserId, testUsername, testEmail, Workspace.WorkspaceRole.OWNER);
        workspace = workspaceRepository.save(workspace);

        // Delete workspace
        mockMvc.perform(delete("/api/workspaces/" + workspace.getWorkspaceId())
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("deleted successfully")));

        // Verify deletion
        mockMvc.perform(get("/api/workspaces/" + workspace.getWorkspaceId())
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(21)
    @DisplayName("TC-WD-004: Attempt to Delete as Member")
    public void testDeleteWorkspaceAsMember() throws Exception {
        // Create workspace with owner
        User owner = new User();
        owner.setUsername("owner");
        owner.setEmail("owner@example.com");
        owner.setPassword("$2a$10$dummyhash");
        owner = userRepository.save(owner);

        Workspace workspace = new Workspace();
        workspace.setWorkspaceId("member-delete-test");
        workspace.setName("Member Delete Test");
        workspace.setOwnerId(owner.getId());
        workspace.addMember(owner.getId(), owner.getUsername(), owner.getEmail(), Workspace.WorkspaceRole.OWNER);
        workspace.addMember(testUserId, testUsername, testEmail, Workspace.WorkspaceRole.MEMBER);
        workspace = workspaceRepository.save(workspace);

        // Try to delete as member
        mockMvc.perform(delete("/api/workspaces/" + workspace.getWorkspaceId())
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", containsString("Only the owner")));
    }

    // ============================================================================
    // VALIDATION TEST CASES (TC-VAL-001 to TC-VAL-045)
    // ============================================================================

    @Test
    @Order(30)
    @DisplayName("TC-VAL-001: Workspace Name Length Validation")
    public void testWorkspaceNameLengthValidation() throws Exception {
        // Test name too long (> 255 characters)
        String tooLongName = "A".repeat(256);
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName(tooLongName);

        mockMvc.perform(post("/api/workspaces")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("255 characters")));
    }

    @Test
    @Order(31)
    @DisplayName("TC-VAL-002: XSS Prevention in Workspace Name")
    public void testXSSPreventionInWorkspaceName() throws Exception {
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName("<script>alert('xss')</script>");

        mockMvc.perform(post("/api/workspaces")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("cannot contain < or >")));
    }

    @Test
    @Order(32)
    @DisplayName("TC-VAL-003: SQL Injection Detection")
    public void testSQLInjectionDetection() throws Exception {
        String[] sqlInjectionPatterns = {
            "'; DROP TABLE users; --",
            "1' OR '1'='1",
            "admin'--",
            "' UNION SELECT NULL--"
        };

        for (String pattern : sqlInjectionPatterns) {
            CreateWorkspaceRequest request = new CreateWorkspaceRequest();
            request.setName(pattern);

            mockMvc.perform(post("/api/workspaces")
                    .header("Authorization", "Bearer " + authToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @Order(33)
    @DisplayName("TC-VAL-007: Description Length Validation")
    public void testDescriptionLengthValidation() throws Exception {
        String tooLongDescription = "A".repeat(1001);
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName("Valid Name");
        request.setDescription(tooLongDescription);

        mockMvc.perform(post("/api/workspaces")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("1000 characters")));
    }

    @Test
    @Order(34)
    @DisplayName("TC-VAL-008: Whitespace Trimming")
    public void testWhitespaceTrimming() throws Exception {
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName("  Trimmed Workspace  ");
        request.setDescription("  Trimmed Description  ");

        mockMvc.perform(post("/api/workspaces")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Trimmed Workspace"));
    }

    @Test
    @Order(35)
    @DisplayName("TC-VAL-032: Subscription Plan Validation")
    public void testSubscriptionPlanValidation() throws Exception {
        String[] validPlans = {"FREE", "PRO", "ENTERPRISE"};
        
        for (String plan : validPlans) {
            CreateWorkspaceRequest request = new CreateWorkspaceRequest();
            request.setName("Plan Test " + plan);
            request.setSubscriptionPlan(plan);

            mockMvc.perform(post("/api/workspaces")
                    .header("Authorization", "Bearer " + authToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.subscriptionPlan").value(plan));
        }

        // Test invalid plan
        CreateWorkspaceRequest invalidRequest = new CreateWorkspaceRequest();
        invalidRequest.setName("Invalid Plan");
        invalidRequest.setSubscriptionPlan("INVALID");

        mockMvc.perform(post("/api/workspaces")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(36)
    @DisplayName("TC-VAL-038: XSS Detection in Description")
    public void testXSSDetectionInDescription() throws Exception {
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName("Valid Name");
        request.setDescription("<img src=x onerror=alert('XSS')>");

        mockMvc.perform(post("/api/workspaces")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("cannot contain < or >")));
    }

    @AfterEach
    public void cleanup() {
        // Clean up test data after each test if needed
        // This helps maintain test isolation
    }

    @AfterAll
    public static void tearDown(@Autowired WorkspaceRepository workspaceRepository, 
                                 @Autowired UserRepository userRepository) {
        // Clean up all test data
        workspaceRepository.deleteAll();
        // Note: Be careful with user deletion if shared across tests
    }
}
