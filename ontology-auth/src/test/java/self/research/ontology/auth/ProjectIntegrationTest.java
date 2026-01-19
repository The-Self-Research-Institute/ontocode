package self.research.ontology.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import self.research.ontology.auth.dto.ProjectRequests.*;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.ProjectRepository;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;
import self.research.ontology.auth.util.JwtUtil;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Project Flow
 * 
 * Test Categories:
 * - TC-PC: Project Creation (9 test cases)
 * - TC-PM: Project Management (8 test cases)
 * - TC-VAL: Project Validation (13+ test cases)
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ProjectIntegrationTest {

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
    private static String testUsername = "projecttest";
    private static String testEmail = "projecttest@example.com";
    private static String testWorkspaceId;
    private static String authToken;

    @BeforeEach
    public void setup() {
        // Create test user
        User testUser = userRepository.findByUsername(testUsername)
                .orElseGet(() -> {
                    User user = new User();
                    user.setUsername(testUsername);
                    user.setEmail(testEmail);
                    user.setPassword("$2a$10$dummyhash");
                    return userRepository.save(user);
                });
        
        testUserId = testUser.getId();

        // Create test workspace
        Workspace workspace = new Workspace();
        workspace.setWorkspaceId("test-project-workspace");
        workspace.setName("Project Test Workspace");
        workspace.setOwnerId(testUserId);
        workspace.addMember(testUserId, testUsername, testEmail, Workspace.WorkspaceRole.OWNER);
        workspace.setSubscriptionPlan("FREE");
        workspace.setMaxWorkspaces(3);
        workspace.setMaxMembers(10);
        workspace = workspaceRepository.save(workspace);
        testWorkspaceId = workspace.getWorkspaceId();

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", testUserId);
        claims.put("workspaceId", testWorkspaceId);
        claims.put("email", testEmail);
        authToken = jwtUtil.generateToken(testUsername, claims);
    }

    // ============================================================================
    // PROJECT CREATION TEST CASES (TC-PC-001 to TC-PC-009)
    // ============================================================================

    @Test
    @Order(1)
    @DisplayName("TC-PC-001: Create Project with Valid Data")
    public void testCreateProjectWithValidData() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setWorkspaceId(testWorkspaceId);
        request.setName("Test Project");
        request.setDescription("Test project description");
        request.setShareWith("all");

        mockMvc.perform(post("/api/projects")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").exists())
                .andExpect(jsonPath("$.name").value("Test Project"))
                .andExpect(jsonPath("$.description").value("Test project description"))
                .andExpect(jsonPath("$.workspaceId").value(testWorkspaceId))
                .andExpect(jsonPath("$.ownerId").value(testUserId));
    }

    @Test
    @Order(2)
    @DisplayName("TC-PC-002: Create Project Shared with All")
    public void testCreateProjectSharedWithAll() throws Exception {
        // First add another member to workspace
        User member = new User();
        member.setUsername("member1");
        member.setEmail("member1@example.com");
        member.setPassword("$2a$10$dummyhash");
        member = userRepository.save(member);

        Workspace workspace = workspaceRepository.findById(testWorkspaceId).get();
        workspace.addMember(member.getId(), member.getUsername(), member.getEmail(), Workspace.WorkspaceRole.MEMBER);
        workspaceRepository.save(workspace);

        CreateProjectRequest request = new CreateProjectRequest();
        request.setWorkspaceId(testWorkspaceId);
        request.setName("Shared Project");
        request.setDescription("Shared with all members");
        request.setShareWith("all");

        mockMvc.perform(post("/api/projects")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharedWith").isArray())
                .andExpect(jsonPath("$.sharedWith", hasSize(greaterThanOrEqualTo(2))));
    }

    @Test
    @Order(3)
    @DisplayName("TC-PC-003: Create Project with Specific Members")
    public void testCreateProjectWithSpecificMembers() throws Exception {
        User member = new User();
        member.setUsername("specific_member");
        member.setEmail("specific@example.com");
        member.setPassword("$2a$10$dummyhash");
        member = userRepository.save(member);

        CreateProjectRequest request = new CreateProjectRequest();
        request.setWorkspaceId(testWorkspaceId);
        request.setName("Specific Share Project");
        request.setDescription("Shared with specific members");
        request.setShareWith("specific");
        request.setMemberEmails(Arrays.asList("specific@example.com"));

        mockMvc.perform(post("/api/projects")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharedWith").isArray());
    }

    @Test
    @Order(4)
    @DisplayName("TC-PC-004: Create Project with Duplicate Name")
    public void testCreateProjectWithDuplicateName() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setWorkspaceId(testWorkspaceId);
        request.setName("Duplicate Project");
        request.setDescription("First project");

        // Create first project
        mockMvc.perform(post("/api/projects")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // Try to create duplicate
        mockMvc.perform(post("/api/projects")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("already exists")));
    }

    @Test
    @Order(5)
    @DisplayName("TC-PC-005: Create Project with Empty Name")
    public void testCreateProjectWithEmptyName() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setWorkspaceId(testWorkspaceId);
        request.setName("");
        request.setDescription("Test description");

        mockMvc.perform(post("/api/projects")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Project name is required")));
    }

    @Test
    @Order(6)
    @DisplayName("TC-PC-006: Create Project with Special Characters")
    public void testCreateProjectWithSpecialCharacters() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setWorkspaceId(testWorkspaceId);
        request.setName("Test/Invalid\\Project:Name");
        request.setDescription("Test description");

        mockMvc.perform(post("/api/projects")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("special characters")));
    }

    @Test
    @Order(7)
    @DisplayName("TC-PC-007: Create Project Without Workspace Context")
    public void testCreateProjectWithoutWorkspaceContext() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setWorkspaceId("non-existent-workspace");
        request.setName("Test Project");
        request.setDescription("Test description");

        mockMvc.perform(post("/api/projects")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Workspace")));
    }

    @Test
    @Order(8)
    @DisplayName("TC-PC-008: Create Project with Long Description")
    public void testCreateProjectWithLongDescription() throws Exception {
        String longDescription = "A".repeat(1001);
        CreateProjectRequest request = new CreateProjectRequest();
        request.setWorkspaceId(testWorkspaceId);
        request.setName("Long Desc Project");
        request.setDescription(longDescription);

        mockMvc.perform(post("/api/projects")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("1000 characters")));
    }

    @Test
    @Order(9)
    @DisplayName("TC-PC-009: Check Project Name Availability")
    public void testCheckProjectNameAvailability() throws Exception {
        // Create a project first
        CreateProjectRequest request = new CreateProjectRequest();
        request.setWorkspaceId(testWorkspaceId);
        request.setName("Existing Project");
        request.setDescription("Test");

        mockMvc.perform(post("/api/projects")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // Check if name exists
        mockMvc.perform(get("/api/projects/check")
                .header("Authorization", "Bearer " + authToken)
                .param("name", "Existing Project")
                .param("workspaceId", testWorkspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true));

        // Check if name doesn't exist
        mockMvc.perform(get("/api/projects/check")
                .header("Authorization", "Bearer " + authToken)
                .param("name", "Non Existing Project")
                .param("workspaceId", testWorkspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false));
    }

    // ============================================================================
    // PROJECT MANAGEMENT TEST CASES (TC-PM-001 to TC-PM-008)
    // ============================================================================

    @Test
    @Order(20)
    @DisplayName("TC-PM-001: Rename Project")
    public void testRenameProject() throws Exception {
        // Create project
        CreateProjectRequest createRequest = new CreateProjectRequest();
        createRequest.setWorkspaceId(testWorkspaceId);
        createRequest.setName("Original Name");
        createRequest.setDescription("Test");

        String projectId = mockMvc.perform(post("/api/projects")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Extract project ID from response
        String pId = objectMapper.readTree(projectId).get("projectId").asText();

        // Rename project
        UpdateProjectRequest updateRequest = new UpdateProjectRequest();
        updateRequest.setName("New Name");

        mockMvc.perform(put("/api/projects/" + pId)
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"));
    }

    @Test
    @Order(21)
    @DisplayName("TC-PM-002: Delete Project")
    public void testDeleteProject() throws Exception {
        // Create project
        CreateProjectRequest createRequest = new CreateProjectRequest();
        createRequest.setWorkspaceId(testWorkspaceId);
        createRequest.setName("To Delete");
        createRequest.setDescription("Test");

        String projectId = mockMvc.perform(post("/api/projects")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String pId = objectMapper.readTree(projectId).get("projectId").asText();

        // Delete project
        mockMvc.perform(delete("/api/projects/" + pId)
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("deleted")));

        // Verify deletion
        mockMvc.perform(get("/api/projects/" + pId)
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(22)
    @DisplayName("TC-PM-004: List Projects in Workspace")
    public void testListProjectsInWorkspace() throws Exception {
        // Create multiple projects
        for (int i = 1; i <= 3; i++) {
            CreateProjectRequest request = new CreateProjectRequest();
            request.setWorkspaceId(testWorkspaceId);
            request.setName("List Project " + i);
            request.setDescription("Test " + i);

            mockMvc.perform(post("/api/projects")
                    .header("Authorization", "Bearer " + authToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        // List all projects
        mockMvc.perform(get("/api/projects")
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myFiles").isArray())
                .andExpect(jsonPath("$.myFiles", hasSize(greaterThanOrEqualTo(3))));
    }

    @Test
    @Order(23)
    @DisplayName("TC-PM-007: Delete Project Without Permission")
    public void testDeleteProjectWithoutPermission() throws Exception {
        // Create project with owner
        User owner = new User();
        owner.setUsername("project_owner");
        owner.setEmail("owner@example.com");
        owner.setPassword("$2a$10$dummyhash");
        owner = userRepository.save(owner);

        Workspace workspace = workspaceRepository.findById(testWorkspaceId).get();
        workspace.addMember(owner.getId(), owner.getUsername(), owner.getEmail(), Workspace.WorkspaceRole.MEMBER);
        workspaceRepository.save(workspace);

        Map<String, Object> ownerClaims = new HashMap<>();
        ownerClaims.put("userId", owner.getId());
        ownerClaims.put("workspaceId", testWorkspaceId);
        ownerClaims.put("email", owner.getEmail());
        String ownerToken = jwtUtil.generateToken(owner.getUsername(), ownerClaims);

        CreateProjectRequest request = new CreateProjectRequest();
        request.setWorkspaceId(testWorkspaceId);
        request.setName("Owner Project");
        request.setDescription("Test");

        String projectId = mockMvc.perform(post("/api/projects")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String pId = objectMapper.readTree(projectId).get("projectId").asText();

        // Try to delete with different user (testUser)
        mockMvc.perform(delete("/api/projects/" + pId)
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", containsString("permission")));
    }

    // ============================================================================
    // PROJECT VALIDATION TEST CASES (TC-VAL-013 to TC-VAL-020)
    // ============================================================================

    @Test
    @Order(30)
    @DisplayName("TC-VAL-013: Project Name Length Validation")
    public void testProjectNameLengthValidation() throws Exception {
        String tooLongName = "A".repeat(256);
        CreateProjectRequest request = new CreateProjectRequest();
        request.setWorkspaceId(testWorkspaceId);
        request.setName(tooLongName);

        mockMvc.perform(post("/api/projects")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("255 characters")));
    }

    @Test
    @Order(31)
    @DisplayName("TC-VAL-015: Path Traversal Prevention")
    public void testPathTraversalPrevention() throws Exception {
        String[] pathTraversalPatterns = {
            "../etc/passwd",
            "..\\windows\\system32",
            "test/../../../etc",
            ".\\..\\admin"
        };

        for (String pattern : pathTraversalPatterns) {
            CreateProjectRequest request = new CreateProjectRequest();
            request.setWorkspaceId(testWorkspaceId);
            request.setName(pattern);

            mockMvc.perform(post("/api/projects")
                    .header("Authorization", "Bearer " + authToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", anyOf(
                        containsString("path traversal"),
                        containsString("special characters")
                    )));
        }
    }

    @Test
    @Order(32)
    @DisplayName("TC-VAL-017: Member Email List Size Validation")
    public void testMemberEmailListSizeValidation() throws Exception {
        // Create list with more than 100 emails
        String[] emails = new String[101];
        for (int i = 0; i < 101; i++) {
            emails[i] = "user" + i + "@example.com";
        }

        CreateProjectRequest request = new CreateProjectRequest();
        request.setWorkspaceId(testWorkspaceId);
        request.setName("Large Share Project");
        request.setShareWith("specific");
        request.setMemberEmails(Arrays.asList(emails));

        mockMvc.perform(post("/api/projects")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("100 members")));
    }

    @AfterEach
    public void cleanup() {
        // Clean up test data
    }

    @AfterAll
    public static void tearDown(@Autowired ProjectRepository projectRepository,
                                 @Autowired WorkspaceRepository workspaceRepository) {
        // Clean up all test data
        projectRepository.deleteAll();
        workspaceRepository.deleteAll();
    }
}
