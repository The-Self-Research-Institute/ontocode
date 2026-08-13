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

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MemberAndSubscriptionTest {

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
    private static String testUsername = "membertest";
    private static String testEmail = "membertest@example.com";
    private static String testWorkspaceId;
    private static String authToken;

    @BeforeEach
    public void setup() {

        User testUser = userRepository.findByUsername(testUsername)
                .orElseGet(() -> {
                    User user = new User();
                    user.setUsername(testUsername);
                    user.setEmail(testEmail);
                    user.setPassword("$2a$10$dummyhash");
                    return userRepository.save(user);
                });

        testUserId = testUser.getId();

        Workspace workspace = new Workspace();
        workspace.setWorkspaceId("member-test-workspace");
        workspace.setName("Member Test Workspace");
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

    @Test
    @Order(1)
    @DisplayName("TC-WM-001: Add Member by Email")
    public void testAddMemberByEmail() throws Exception {

        User newMember = new User();
        newMember.setUsername("newmember");
        newMember.setEmail("newmember@example.com");
        newMember.setPassword("$2a$10$dummyhash");
        userRepository.save(newMember);

        AddMemberRequest request = new AddMemberRequest();
        request.setEmail("newmember@example.com");
        request.setRole("MEMBER");

        mockMvc.perform(post("/api/workspaces/" + testWorkspaceId + "/members")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("added")))
                .andExpect(jsonPath("$.member.email").value("newmember@example.com"))
                .andExpect(jsonPath("$.member.role").value("MEMBER"));
    }

    @Test
    @Order(2)
    @DisplayName("TC-WM-002: Add Member with VIEWER Role")
    public void testAddMemberWithViewerRole() throws Exception {
        User viewer = new User();
        viewer.setUsername("viewer");
        viewer.setEmail("viewer@example.com");
        viewer.setPassword("$2a$10$dummyhash");
        userRepository.save(viewer);

        AddMemberRequest request = new AddMemberRequest();
        request.setEmail("viewer@example.com");
        request.setRole("VIEWER");

        mockMvc.perform(post("/api/workspaces/" + testWorkspaceId + "/members")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member.role").value("VIEWER"));
    }

    @Test
    @Order(3)
    @DisplayName("TC-WM-003: Remove Member from Workspace")
    public void testRemoveMemberFromWorkspace() throws Exception {

        User member = new User();
        member.setUsername("removeme");
        member.setEmail("removeme@example.com");
        member.setPassword("$2a$10$dummyhash");
        member = userRepository.save(member);

        Workspace workspace = workspaceRepository.findById(testWorkspaceId).get();
        workspace.addMember(member.getId(), member.getUsername(), member.getEmail(), Workspace.WorkspaceRole.MEMBER);
        workspaceRepository.save(workspace);

        mockMvc.perform(delete("/api/workspaces/" + testWorkspaceId + "/members/" + member.getId())
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("removed")));
    }

    @Test
    @Order(4)
    @DisplayName("TC-WM-004: Update Member Role")
    public void testUpdateMemberRole() throws Exception {

        User member = new User();
        member.setUsername("updaterole");
        member.setEmail("updaterole@example.com");
        member.setPassword("$2a$10$dummyhash");
        member = userRepository.save(member);

        Workspace workspace = workspaceRepository.findById(testWorkspaceId).get();
        workspace.addMember(member.getId(), member.getUsername(), member.getEmail(), Workspace.WorkspaceRole.VIEWER);
        workspaceRepository.save(workspace);

        UpdateMemberRoleRequest request = new UpdateMemberRoleRequest();
        request.setRole("MEMBER");

        mockMvc.perform(put("/api/workspaces/" + testWorkspaceId + "/members/" + member.getId())
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member.role").value("MEMBER"));
    }

    @Test
    @Order(5)
    @DisplayName("TC-WM-005: Add Member Exceeding Limit")
    public void testAddMemberExceedingLimit() throws Exception {

        for (int i = 1; i < 10; i++) {
            User member = new User();
            member.setUsername("member" + i);
            member.setEmail("member" + i + "@example.com");
            member.setPassword("$2a$10$dummyhash");
            member = userRepository.save(member);

            Workspace workspace = workspaceRepository.findById(testWorkspaceId).get();
            workspace.addMember(member.getId(), member.getUsername(), member.getEmail(), Workspace.WorkspaceRole.VIEWER);
            workspaceRepository.save(workspace);
        }

        User extraMember = new User();
        extraMember.setUsername("extra");
        extraMember.setEmail("extra@example.com");
        extraMember.setPassword("$2a$10$dummyhash");
        userRepository.save(extraMember);

        AddMemberRequest request = new AddMemberRequest();
        request.setEmail("extra@example.com");
        request.setRole("VIEWER");

        mockMvc.perform(post("/api/workspaces/" + testWorkspaceId + "/members")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Maximum member limit")));
    }

    @Test
    @Order(6)
    @DisplayName("TC-WM-006: Add Member with Invalid Email")
    public void testAddMemberWithInvalidEmail() throws Exception {
        String[] invalidEmails = {
            "notanemail",
            "@example.com",
            "user@",
            "user @example.com",
            "user@.com"
        };

        for (String email : invalidEmails) {
            AddMemberRequest request = new AddMemberRequest();
            request.setEmail(email);
            request.setRole("VIEWER");

            mockMvc.perform(post("/api/workspaces/" + testWorkspaceId + "/members")
                    .header("Authorization", "Bearer " + authToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", containsString("email")));
        }
    }

    @Test
    @Order(7)
    @DisplayName("TC-WM-007: Add Duplicate Member")
    public void testAddDuplicateMember() throws Exception {
        User member = new User();
        member.setUsername("duplicate");
        member.setEmail("duplicate@example.com");
        member.setPassword("$2a$10$dummyhash");
        member = userRepository.save(member);

        Workspace workspace = workspaceRepository.findById(testWorkspaceId).get();
        workspace.addMember(member.getId(), member.getUsername(), member.getEmail(), Workspace.WorkspaceRole.VIEWER);
        workspaceRepository.save(workspace);

        AddMemberRequest request = new AddMemberRequest();
        request.setEmail("duplicate@example.com");
        request.setRole("MEMBER");

        mockMvc.perform(post("/api/workspaces/" + testWorkspaceId + "/members")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("already a member")));
    }

    @Test
    @Order(8)
    @DisplayName("TC-WM-008: Remove Workspace Owner")
    public void testRemoveWorkspaceOwner() throws Exception {
        mockMvc.perform(delete("/api/workspaces/" + testWorkspaceId + "/members/" + testUserId)
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Cannot remove owner")));
    }

    @Test
    @Order(9)
    @DisplayName("TC-WM-009: Non-Owner Attempts to Add Member")
    public void testNonOwnerAddsMember() throws Exception {

        User editor = new User();
        editor.setUsername("editor");
        editor.setEmail("editor@example.com");
        editor.setPassword("$2a$10$dummyhash");
        editor = userRepository.save(editor);

        Workspace workspace = workspaceRepository.findById(testWorkspaceId).get();
        workspace.addMember(editor.getId(), editor.getUsername(), editor.getEmail(), Workspace.WorkspaceRole.MEMBER);
        workspaceRepository.save(workspace);

        Map<String, Object> editorClaims2 = new HashMap<>();
        editorClaims2.put("userId", editor.getId());
        editorClaims2.put("workspaceId", testWorkspaceId);
        editorClaims2.put("email", editor.getEmail());
        String editorToken = jwtUtil.generateToken(editor.getUsername(), editorClaims2);

        User newMember = new User();
        newMember.setUsername("newuser");
        newMember.setEmail("newuser@example.com");
        newMember.setPassword("$2a$10$dummyhash");
        userRepository.save(newMember);

        AddMemberRequest request = new AddMemberRequest();
        request.setEmail("newuser@example.com");
        request.setRole("VIEWER");

        mockMvc.perform(post("/api/workspaces/" + testWorkspaceId + "/members")
                .header("Authorization", "Bearer " + editorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", containsString("Only the owner")));
    }

    @Test
    @Order(10)
    @DisplayName("TC-WM-010: View Workspace Member List")
    public void testViewWorkspaceMemberList() throws Exception {
        mockMvc.perform(get("/api/workspaces/" + testWorkspaceId + "/members")
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members").isArray())
                .andExpect(jsonPath("$.members[0].userId").exists())
                .andExpect(jsonPath("$.members[0].username").exists())
                .andExpect(jsonPath("$.members[0].email").exists())
                .andExpect(jsonPath("$.members[0].role").exists());
    }

    @Test
    @Order(20)
    @DisplayName("TC-SUB-001: View Current Subscription")
    public void testViewCurrentSubscription() throws Exception {
        mockMvc.perform(get("/api/workspaces/" + testWorkspaceId + "/subscription")
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionPlan").value("FREE"))
                .andExpect(jsonPath("$.maxWorkspaces").value(3))
                .andExpect(jsonPath("$.maxMembers").value(10));
    }

    @Test
    @Order(21)
    @DisplayName("TC-SUB-002: Upgrade Subscription Plan")
    public void testUpgradeSubscriptionPlan() throws Exception {
        UpdateSubscriptionRequest request = new UpdateSubscriptionRequest();
        request.setSubscriptionPlan("PRO");

        mockMvc.perform(put("/api/workspaces/" + testWorkspaceId + "/subscription")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionPlan").value("PRO"))
                .andExpect(jsonPath("$.maxWorkspaces").value(10))
                .andExpect(jsonPath("$.maxMembers").value(50));
    }

    @Test
    @Order(22)
    @DisplayName("TC-SUB-003: Downgrade Subscription")
    public void testDowngradeSubscription() throws Exception {

        UpdateSubscriptionRequest upgradeRequest = new UpdateSubscriptionRequest();
        upgradeRequest.setSubscriptionPlan("ENTERPRISE");

        mockMvc.perform(put("/api/workspaces/" + testWorkspaceId + "/subscription")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(upgradeRequest)))
                .andExpect(status().isOk());

        UpdateSubscriptionRequest downgradeRequest = new UpdateSubscriptionRequest();
        downgradeRequest.setSubscriptionPlan("PRO");

        mockMvc.perform(put("/api/workspaces/" + testWorkspaceId + "/subscription")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(downgradeRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionPlan").value("PRO"));
    }

    @Test
    @Order(23)
    @DisplayName("TC-SUB-004: Downgrade with Exceeding Usage")
    public void testDowngradeWithExceedingUsage() throws Exception {

        for (int i = 1; i <= 12; i++) {
            User member = new User();
            member.setUsername("submember" + i);
            member.setEmail("submember" + i + "@example.com");
            member.setPassword("$2a$10$dummyhash");
            member = userRepository.save(member);

            Workspace workspace = workspaceRepository.findById(testWorkspaceId).get();
            workspace.addMember(member.getId(), member.getUsername(), member.getEmail(), Workspace.WorkspaceRole.VIEWER);
            workspaceRepository.save(workspace);
        }

        UpdateSubscriptionRequest request = new UpdateSubscriptionRequest();
        request.setSubscriptionPlan("FREE");

        mockMvc.perform(put("/api/workspaces/" + testWorkspaceId + "/subscription")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("current usage exceeds")));
    }

    @Test
    @Order(24)
    @DisplayName("TC-SUB-005: View Subscription History")
    public void testViewSubscriptionHistory() throws Exception {
        mockMvc.perform(get("/api/workspaces/" + testWorkspaceId + "/subscription/history")
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionStartDate").exists())
                .andExpect(jsonPath("$.subscriptionPlan").exists());
    }

    @Test
    @Order(30)
    @DisplayName("TC-AC-001: Owner Full Access")
    public void testOwnerFullAccess() throws Exception {

        mockMvc.perform(get("/api/workspaces/" + testWorkspaceId)
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/workspaces/" + testWorkspaceId + "/members")
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk());
    }

    @Test
    @Order(31)
    @DisplayName("TC-AC-002: MEMBER Role Permissions")
    public void testEditorRolePermissions() throws Exception {

        User editor = new User();
        editor.setUsername("editoraccess");
        editor.setEmail("editoraccess@example.com");
        editor.setPassword("$2a$10$dummyhash");
        editor = userRepository.save(editor);

        Workspace workspace = workspaceRepository.findById(testWorkspaceId).get();
        workspace.addMember(editor.getId(), editor.getUsername(), editor.getEmail(), Workspace.WorkspaceRole.MEMBER);
        workspaceRepository.save(workspace);

        Map<String, Object> editorClaims = new HashMap<>();
        editorClaims.put("userId", editor.getId());
        editorClaims.put("workspaceId", testWorkspaceId);
        editorClaims.put("email", editor.getEmail());
        String editorToken = jwtUtil.generateToken(editor.getUsername(), editorClaims);

        mockMvc.perform(get("/api/workspaces/" + testWorkspaceId)
                .header("Authorization", "Bearer " + editorToken))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/workspaces/" + testWorkspaceId)
                .header("Authorization", "Bearer " + editorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(32)
    @DisplayName("TC-AC-003: VIEWER Role Permissions")
    public void testViewerRolePermissions() throws Exception {
        User viewer = new User();
        viewer.setUsername("vieweraccess");
        viewer.setEmail("vieweraccess@example.com");
        viewer.setPassword("$2a$10$dummyhash");
        viewer = userRepository.save(viewer);

        Workspace workspace = workspaceRepository.findById(testWorkspaceId).get();
        workspace.addMember(viewer.getId(), viewer.getUsername(), viewer.getEmail(), Workspace.WorkspaceRole.VIEWER);
        workspaceRepository.save(workspace);

        Map<String, Object> viewerClaims = new HashMap<>();
        viewerClaims.put("userId", viewer.getId());
        viewerClaims.put("workspaceId", testWorkspaceId);
        viewerClaims.put("email", viewer.getEmail());
        String viewerToken = jwtUtil.generateToken(viewer.getUsername(), viewerClaims);

        mockMvc.perform(get("/api/workspaces/" + testWorkspaceId)
                .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());

        UpdateWorkspaceRequest request = new UpdateWorkspaceRequest();
        request.setName("Modified Name");

        mockMvc.perform(put("/api/workspaces/" + testWorkspaceId)
                .header("Authorization", "Bearer " + viewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(33)
    @DisplayName("TC-AC-005: Cross-Workspace Data Isolation")
    public void testCrossWorkspaceDataIsolation() throws Exception {

        Workspace otherWorkspace = new Workspace();
        otherWorkspace.setWorkspaceId("other-workspace");
        otherWorkspace.setName("Other Workspace");
        otherWorkspace.setOwnerId("other-user-id");
        workspaceRepository.save(otherWorkspace);

        mockMvc.perform(get("/api/workspaces/other-workspace")
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isForbidden());
    }

    @AfterEach
    public void cleanup() {

    }

    @AfterAll
    public static void tearDown(@Autowired WorkspaceRepository workspaceRepository) {
        workspaceRepository.deleteAll();
    }
}
