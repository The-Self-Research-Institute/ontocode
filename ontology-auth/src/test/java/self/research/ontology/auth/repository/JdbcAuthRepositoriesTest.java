package self.research.ontology.auth.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import self.research.ontology.auth.model.DesktopDownloadEvent;
import self.research.ontology.auth.model.EmailLog;
import self.research.ontology.auth.model.FileMetadata;
import self.research.ontology.auth.model.Invitation;
import self.research.ontology.auth.model.PlanFeatureConfig;
import self.research.ontology.auth.model.Project;
import self.research.ontology.auth.model.StripeEvent;
import self.research.ontology.auth.model.StripeUnmatchedInvoice;
import self.research.ontology.auth.model.SystemSettings;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.common.db.testing.EmbeddedTestDatabase;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcAuthRepositoriesTest {

    private final JdbcTemplate jdbc = EmbeddedTestDatabase.jdbc();
    private final TransactionTemplate tx = EmbeddedTestDatabase.transactions();
    private final JdbcUserRepository users = new JdbcUserRepository(jdbc);
    private final JdbcWorkspaceRepository workspaces = new JdbcWorkspaceRepository(jdbc, tx);
    private final JdbcProjectRepository projects = new JdbcProjectRepository(jdbc, tx);
    private final JdbcFileMetadataRepository files = new JdbcFileMetadataRepository(jdbc);
    private final JdbcInvitationRepository invitations = new JdbcInvitationRepository(jdbc);
    private final JdbcStripeEventRepository stripeEvents = new JdbcStripeEventRepository(jdbc);
    private final JdbcEmailLogRepository emailLogs = new JdbcEmailLogRepository(jdbc);
    private final JdbcStripeUnmatchedInvoiceRepository unmatched = new JdbcStripeUnmatchedInvoiceRepository(jdbc);
    private final JdbcAuditEventRepository audit = new JdbcAuditEventRepository(jdbc, new ObjectMapper());
    private final JdbcSystemSettingsRepository settings = new JdbcSystemSettingsRepository(jdbc);
    private final JdbcPlanFeatureConfigRepository plans = new JdbcPlanFeatureConfigRepository(jdbc);
    private final JdbcDesktopDownloadEventRepository downloads = new JdbcDesktopDownloadEventRepository(jdbc);
    private final JdbcOntologyFileNameRepository ontologyFileNames = new JdbcOntologyFileNameRepository(jdbc);
    private final JdbcProjectDataPurgeRepository purge = new JdbcProjectDataPurgeRepository(jdbc);

    @BeforeEach
    void clean() {
        for (String table : List.of("project_file", "project_member", "project", "workspace_member", "workspace",
                "file_metadata", "invitation", "stripe_event", "email_log", "stripe_unmatched_invoice",
                "system_settings", "plan_feature_config", "desktop_download_event", "audit_event", "app_user")) {
            jdbc.update("delete from " + table);
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    private User user(String name, String email) {
        User u = new User();
        u.setUsername(name);
        u.setEmail(email);
        u.setPassword("hash");
        u.setRoles(Set.of("ROLE_USER", "ROLE_ADMIN"));
        u.setEnabled(true);
        return users.save(u);
    }

    private Workspace workspace(String workspaceId, String ownerId) {
        Workspace w = new Workspace();
        w.setWorkspaceId(workspaceId);
        w.setOwnerId(ownerId);
        w.setName("Workspace " + workspaceId);
        return workspaces.save(w);
    }

    private Project project(String projectId, String workspaceId, String ownerId) {
        Project p = new Project();
        p.setProjectId(projectId);
        p.setName("Project " + projectId);
        p.setWorkspaceId(workspaceId);
        p.setOwnerId(ownerId);
        return projects.save(p);
    }

    private static <T> List<T> inParallel(int threads, Supplier<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.get();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        return results;
    }

    @Test
    void aUserRoundTripsEveryFieldAndGetsAnObjectIdShapedId() {
        User u = new User();
        u.setUsername("alice");
        u.setEmail("Alice@Example.com");
        u.setPassword("hash");
        u.setRoles(Set.of("ROLE_USER"));
        u.setEnabled(true);
        u.setVerificationToken("vt");
        u.setVerificationTokenExpiry(now().plusHours(1));
        u.setPasswordResetToken("pt");
        u.setLockoutEndTime(now().plusMinutes(5));
        u.setLastLoginAt(now());
        u.setStripeCustomerId("cus_1");
        u.setStripeSubscriptionId("sub_1");
        u.setSubscriptionStatus("active");
        u.setSubscriptionPlanName("PRO");
        u.setSubscriptionCurrentPeriodEnd(now().plusDays(10));
        u.setAutoRenewEnabled(false);
        u.setHasUsedFreeTrial(true);
        u.setPendingBillingInterval("monthly");
        u.setCreatedAt(now());
        u.setUpdatedAt(now());
        users.save(u);

        User loaded = users.findById(u.getId()).orElseThrow();

        assertEquals(24, u.getId().length());
        assertEquals("Alice@Example.com", loaded.getEmail());
        assertEquals(Set.of("ROLE_USER"), loaded.getRoles());
        assertEquals(u.getVerificationTokenExpiry(), loaded.getVerificationTokenExpiry());
        assertEquals(u.getLockoutEndTime(), loaded.getLockoutEndTime());
        assertEquals(u.getSubscriptionCurrentPeriodEnd(), loaded.getSubscriptionCurrentPeriodEnd());
        assertFalse(loaded.isAutoRenewEnabled());
        assertTrue(loaded.isHasUsedFreeTrial());
        assertEquals(u.getId(), users.findByStripeSubscriptionId("sub_1").orElseThrow().getId());
        assertEquals(u.getId(), users.findByStripeCustomerId("cus_1").orElseThrow().getId());
        assertEquals(u.getId(), users.findByVerificationToken("vt").orElseThrow().getId());
        assertEquals(u.getId(), users.findByPasswordResetToken("pt").orElseThrow().getId());
        assertEquals(u.getId(), users.findByUsername("alice").orElseThrow().getId());
    }

    @Test
    void savingAnExistingUserUpdatesItAndEmailLookupsFollowTheirOwnCaseRules() {
        User u = user("bob", "Bob@Example.com");
        u.setSubscriptionPlanName("PRO");
        users.save(u);

        assertEquals(1, users.findAll().size());
        assertEquals("PRO", users.findById(u.getId()).orElseThrow().getSubscriptionPlanName());
        assertTrue(users.findByEmail("Bob@Example.com").isPresent());
        assertTrue(users.findByEmail("bob@example.com").isEmpty());
        assertTrue(users.findByEmailIgnoreCase("bOB@EXAMPLE.COM").isPresent());
        assertTrue(users.findByEmailIgnoreCase("nobody@example.com").isEmpty());
    }

    @Test
    void usernamesAndEmailsThatDifferOnlyByCaseAreRejected() {
        user("carol", "carol@example.com");

        User sameEmailOtherCase = new User();
        sameEmailOtherCase.setUsername("carol2");
        sameEmailOtherCase.setEmail("CAROL@example.com");
        sameEmailOtherCase.setPassword("hash");
        assertThrows(DuplicateKeyException.class, () -> users.save(sameEmailOtherCase));

        User sameUsername = new User();
        sameUsername.setUsername("carol");
        sameUsername.setEmail("other@example.com");
        sameUsername.setPassword("hash");
        assertThrows(DuplicateKeyException.class, () -> users.save(sameUsername));
    }

    @Test
    void deletingAUserRemovesIt() {
        User u = user("dave", "dave@example.com");
        users.delete(u);
        assertTrue(users.findById(u.getId()).isEmpty());
    }

    @Test
    void failedLoginAttemptsAreCountedExactlyUnderConcurrency() throws Exception {
        User u = user("erin", "erin@example.com");

        inParallel(16, () -> users.incrementFailedLoginAttempts(u.getId()));

        assertEquals(16, users.findById(u.getId()).orElseThrow().getFailedLoginAttempts());
        assertEquals(17, users.incrementFailedLoginAttempts(u.getId()));
        assertEquals(0, users.incrementFailedLoginAttempts("missing"));
    }

    @Test
    void exactlyOneCallerWinsTheSubscriptionCreationLockAndTheNextOneWinsAfterRelease() throws Exception {
        User u = user("frank", "frank@example.com");
        LocalDateTime t = now();

        List<Boolean> results = inParallel(12, () -> users.tryClaimSubscriptionCreationLock(u.getId(), t, t.minusSeconds(120)));

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertFalse(users.tryClaimSubscriptionCreationLock(u.getId(), t.plusSeconds(1), t.minusSeconds(119)));
        users.releaseSubscriptionCreationLock(u.getId());
        assertNull(users.findById(u.getId()).orElseThrow().getSubscriptionCreationLockedAt());
        assertTrue(users.tryClaimSubscriptionCreationLock(u.getId(), t.plusSeconds(2), t.minusSeconds(118)));
    }

    @Test
    void aStaleSubscriptionCreationLockCanBeTakenOver() {
        User u = user("grace", "grace@example.com");
        LocalDateTime longAgo = now().minusSeconds(500);
        assertTrue(users.tryClaimSubscriptionCreationLock(u.getId(), longAgo, longAgo.minusSeconds(120)));

        LocalDateTime t = now();
        assertTrue(users.tryClaimSubscriptionCreationLock(u.getId(), t, t.minusSeconds(120)));
        assertFalse(users.tryClaimSubscriptionCreationLock(u.getId(), t, t.minusSeconds(120)));
    }

    @Test
    void savingAUserDoesNotOverwriteAHeldSubscriptionCreationLock() {
        User u = user("heidi", "heidi@example.com");
        LocalDateTime t = now();
        assertTrue(users.tryClaimSubscriptionCreationLock(u.getId(), t, t.minusSeconds(120)));

        User stale = users.findById(u.getId()).orElseThrow();
        stale.setSubscriptionCreationLockedAt(null);
        stale.setSubscriptionStatus("active");
        users.save(stale);

        User after = users.findById(u.getId()).orElseThrow();
        assertEquals("active", after.getSubscriptionStatus());
        assertNotNull(after.getSubscriptionCreationLockedAt());
    }

    @Test
    void aWorkspaceRoundTripsItsMembersInOrderWithRolesAndStatuses() {
        Workspace w = new Workspace();
        w.setWorkspaceId("w1");
        w.setOwnerId("u-owner");
        w.setName("Alpha");
        w.setDescription("first");
        w.setSubscriptionPlan("PRO");
        w.setMaxMembers(10);
        w.setCollaborationEnabled(true);
        w.setSubscriptionCurrentPeriodEnd(now().plusDays(3));
        w.addMember("u-owner", "owner", "owner@example.com", Workspace.WorkspaceRole.OWNER);
        w.addMember("u-2", "second", "Second@Example.com", Workspace.WorkspaceRole.VIEWER);
        w.addPendingMember("pending@example.com", Workspace.WorkspaceRole.MEMBER, "tok-1");
        workspaces.save(w);

        Workspace loaded = workspaces.findByWorkspaceId("w1").orElseThrow();

        assertEquals(24, loaded.getId().length());
        assertEquals("PRO", loaded.getSubscriptionPlan());
        assertEquals(10, loaded.getMaxMembers());
        assertTrue(loaded.isCollaborationEnabled());
        assertEquals(w.getSubscriptionCurrentPeriodEnd(), loaded.getSubscriptionCurrentPeriodEnd());
        assertEquals(3, loaded.getMembers().size());
        assertEquals(Workspace.WorkspaceRole.VIEWER, loaded.getMember("u-2").getRole());
        assertEquals(Workspace.MemberStatus.PENDING, loaded.getMemberByEmail("pending@example.com").getStatus());
        assertNotNull(loaded.getPendingMemberByToken("tok-1"));
        assertTrue(loaded.isMemberByEmail("second@example.com"));
    }

    @Test
    void savingAWorkspaceAgainReplacesItsMembers() {
        Workspace w = workspace("w1", "u-owner");
        w.addMember("u-1", "one", "one@example.com", Workspace.WorkspaceRole.MEMBER);
        w.addMember("u-2", "two", "two@example.com", Workspace.WorkspaceRole.MEMBER);
        workspaces.save(w);
        w.removeMember("u-1");
        w.updateMemberRole("u-2", Workspace.WorkspaceRole.ADMIN);
        workspaces.save(w);

        Workspace loaded = workspaces.findById(w.getId()).orElseThrow();
        assertEquals(1, loaded.getMembers().size());
        assertEquals(Workspace.WorkspaceRole.ADMIN, loaded.getMember("u-2").getRole());
        assertEquals(1, jdbc.queryForObject("select count(*) from workspace_member", Integer.class));
    }

    @Test
    void workspaceIdMustBeUnique() {
        workspace("w1", "u1");
        Workspace duplicate = new Workspace();
        duplicate.setWorkspaceId("w1");
        duplicate.setOwnerId("u2");
        assertThrows(DuplicateKeyException.class, () -> workspaces.save(duplicate));
    }

    @Test
    void ownerOrMemberLookupReturnsEachWorkspaceOnceAndHonoursSoftDelete() {
        Workspace both = workspace("w-both", "u1");
        both.addMember("u1", "one", "one@example.com", Workspace.WorkspaceRole.OWNER);
        both.addMember("u1", "one-again", "one@example.com", Workspace.WorkspaceRole.OWNER);
        both.getMembers().add(new Workspace.WorkspaceMember("u1", "dup", "one@example.com", Workspace.WorkspaceRole.OWNER));
        workspaces.save(both);

        Workspace memberOnly = workspace("w-member", "u2");
        memberOnly.addMember("u1", "one", "ONE@example.com", Workspace.WorkspaceRole.VIEWER);
        workspaces.save(memberOnly);

        Workspace deleted = workspace("w-deleted", "u1");
        deleted.setIsDeleted(true);
        deleted.setDeletedAt(now().minusDays(40));
        workspaces.save(deleted);

        workspace("w-other", "u3");

        assertEquals(3, workspaces.findAllUserWorkspaces("u1").size());
        List<Workspace> active = workspaces.findAllActiveUserWorkspaces("u1");
        assertEquals(2, active.size());
        assertEquals(1, workspaces.findDeletedUserWorkspaces("u1").size());
        assertEquals(1, workspaces.findActiveByOwnerId("u1").size());
        assertEquals(2, workspaces.findByOwnerId("u1").size());
        assertEquals(2, workspaces.findByMembers_UserId("u1").size());
        assertEquals(2, workspaces.findActiveByMemberEmail("one@EXAMPLE.com").size());
        assertTrue(workspaces.findActiveByMemberEmail("nobody@example.com").isEmpty());
        assertTrue(workspaces.findActiveByWorkspaceId("w-deleted").isEmpty());
        assertTrue(workspaces.findByWorkspaceId("w-deleted").isPresent());
        assertEquals(1, workspaces.findAllByIsDeletedTrueAndDeletedAtBefore(now().minusDays(30)).size());
        assertTrue(workspaces.findAllByIsDeletedTrueAndDeletedAtBefore(now().minusDays(50)).isEmpty());
    }

    @Test
    void aWorkspaceWithAMissingDeletedFlagCountsAsActive() {
        Workspace w = workspace("w-null", "u1");
        jdbc.update("update workspace set is_deleted = null where id = ?", w.getId());

        assertTrue(workspaces.findActiveByWorkspaceId("w-null").isPresent());
        assertEquals(1, workspaces.findAllActiveUserWorkspaces("u1").size());
        assertTrue(workspaces.findDeletedUserWorkspaces("u1").isEmpty());
    }

    @Test
    void workspaceLookupsByBillingReferences() {
        Workspace w = workspace("w1", "u1");
        w.setStripeSubscriptionId("sub_w");
        w.setPendingCheckoutSessionId("cs_1");
        workspaces.save(w);

        assertTrue(workspaces.existsByWorkspaceId("w1"));
        assertFalse(workspaces.existsByWorkspaceId("nope"));
        assertEquals("w1", workspaces.findByStripeSubscriptionId("sub_w").orElseThrow().getWorkspaceId());
        assertEquals("w1", workspaces.findByPendingCheckoutSessionId("cs_1").orElseThrow().getWorkspaceId());
    }

    @Test
    void deletingAWorkspaceRemovesItsMembers() {
        Workspace w = workspace("w1", "u1");
        w.addMember("u-1", "one", "one@example.com", Workspace.WorkspaceRole.MEMBER);
        workspaces.save(w);
        workspaces.deleteAll(List.of(w));

        assertEquals(0, jdbc.queryForObject("select count(*) from workspace", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from workspace_member", Integer.class));
    }

    @Test
    void aProjectRoundTripsMembersFilesTagsAndLegacyFileIds() {
        Project p = new Project();
        p.setProjectId("p1");
        p.setName("Ontology");
        p.setDescription("desc");
        p.setWorkspaceId("w1");
        p.setOwnerId("u1");
        p.setVisibility("WORKSPACE");
        p.setTags(new ArrayList<>(List.of("a", "b")));
        p.addMember("u1", "owner", "owner@example.com", "OWNER");
        p.addMember("u2", "editor", "Editor@Example.com", "EDITOR", Project.WS_EDITOR_LINK_ADMIN);
        Project.FileMetadataInfo f1 = new Project.FileMetadataInfo("f1", "one.owl", 10L, "application/rdf+xml", "owl");
        f1.setUploadedBy("u1");
        f1.setUploaderUsername("owner");
        f1.setUploaderEmail("owner@example.com");
        p.addFileMetadata(f1);
        p.addFileMetadata(new Project.FileMetadataInfo("f2", "two.ttl", 20L, "text/turtle", "ttl"));
        p.getFiles().get(1).setStatus("DELETED");
        projects.save(p);

        Project loaded = projects.findByProjectId("p1").orElseThrow();

        assertEquals(24, loaded.getId().length());
        assertEquals("WORKSPACE", loaded.getVisibility());
        assertEquals(List.of("a", "b"), loaded.getTags());
        assertEquals(List.of("f1", "f2"), loaded.getFileIds());
        assertEquals(2, loaded.getMembers().size());
        assertEquals(Project.WS_EDITOR_LINK_ADMIN, loaded.getMember("u2").getWorkspaceEditorLink());
        assertEquals(2, loaded.getFiles().size());
        assertEquals("one.owl", loaded.getFiles().get(0).getFileName());
        assertEquals(10L, loaded.getFiles().get(0).getFileSize());
        assertEquals("owner@example.com", loaded.getFiles().get(0).getUploaderEmail());
        assertEquals(1, loaded.getActiveFiles().size());
        assertNull(loaded.getFile("f2"));
    }

    @Test
    void savingAProjectAgainReplacesMembersAndFiles() {
        Project p = project("p1", "w1", "u1");
        p.addMember("u2", "two", "two@example.com", "VIEWER");
        p.addFileMetadata(new Project.FileMetadataInfo("f1", "one.owl", 1L, "t", "owl"));
        projects.save(p);
        p.removeMember("u2");
        p.getFile("f1").setStatus("DELETED");
        p.setName("Renamed");
        projects.save(p);

        Project loaded = projects.findById(p.getId()).orElseThrow();
        assertEquals("Renamed", loaded.getName());
        assertTrue(loaded.getMembers().isEmpty());
        assertEquals("DELETED", loaded.getFiles().get(0).getStatus());
        assertEquals(0, jdbc.queryForObject("select count(*) from project_member", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from project_file", Integer.class));
    }

    @Test
    void projectIdMustBeUniqueAndListsStayConsistent() {
        project("p1", "w1", "u1");
        Project duplicate = new Project();
        duplicate.setProjectId("p1");
        assertThrows(DuplicateKeyException.class, () -> projects.save(duplicate));

        assertEquals(1, projects.findAllByProjectId("p1").size());
        assertEquals(1, projects.findAllActiveByProjectId("p1").size());
        assertTrue(projects.existsByProjectId("p1"));
        assertFalse(projects.existsByProjectId("p2"));
    }

    @Test
    void projectQueriesFilterByWorkspaceOwnerStatusMemberAndSoftDelete() {
        project("p1", "w1", "u1");
        Project archived = project("p2", "w1", "u1");
        archived.setStatus("ARCHIVED");
        projects.save(archived);
        Project gone = project("p3", "w1", "u2");
        gone.setIsDeleted(true);
        gone.setDeletedAt(now().minusDays(31));
        projects.save(gone);
        Project shared = project("p4", "w2", "u3");
        shared.addMember("u1", "one", "ONE@example.com", "EDITOR");
        projects.save(shared);

        assertEquals(3, projects.findByWorkspaceId("w1").size());
        assertEquals(2, projects.findActiveByWorkspaceId("w1").size());
        assertEquals(1, projects.findByWorkspaceIdAndStatus("w1", "ARCHIVED").size());
        assertEquals(2, projects.findByOwnerId("u1").size());
        assertEquals(1, projects.findByOwnerIdAndStatus("u1", "ARCHIVED").size());
        assertEquals(1, projects.findByMembers_UserId("u1").size());
        assertTrue(projects.findActiveByProjectId("p3").isEmpty());
        assertTrue(projects.findByProjectId("p3").isPresent());
        assertEquals(1, projects.findAllByIsDeletedTrueAndDeletedAtBefore(now().minusDays(30)).size());
    }

    @Test
    void theSharedProjectTablesAnswerTheEditorsAccessCheck() {
        Project p = project("p1", "w1", "u1");
        p.addMember("u2", "two", "Two@Example.com", "EDITOR");
        projects.save(p);
        String sql = "select exists (select 1 from project p where p.project_id = ? and coalesce(p.is_deleted, false) = false "
                + "and (p.owner_id = ? or exists (select 1 from project_member m where m.project_row_id = p.id "
                + "and (m.user_id = ? or lower(m.email) = lower(?)))))";

        assertTrue(jdbc.queryForObject(sql, Boolean.class, "p1", "u1", "x", "x"));
        assertTrue(jdbc.queryForObject(sql, Boolean.class, "p1", "x", "u2", "x"));
        assertTrue(jdbc.queryForObject(sql, Boolean.class, "p1", "x", "x", "two@example.com"));
        assertFalse(jdbc.queryForObject(sql, Boolean.class, "p1", "x", "x", "three@example.com"));
    }

    @Test
    void deletingAProjectRemovesItsChildRows() {
        Project p = project("p1", "w1", "u1");
        p.addMember("u2", "two", "two@example.com", "VIEWER");
        p.addFileMetadata(new Project.FileMetadataInfo("f1", "one.owl", 1L, "t", "owl"));
        projects.save(p);

        projects.delete(p);

        assertEquals(0, jdbc.queryForObject("select count(*) from project", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from project_member", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from project_file", Integer.class));
    }

    @Test
    void fileMetadataRoundTripsAndIsQueriedByProjectWorkspaceAndStatus() {
        FileMetadata f = new FileMetadata("file-1", "one.owl", "p1", "w1");
        f.setFileSize(123L);
        f.setFileType("application/rdf+xml");
        f.setExtension("owl");
        f.setBlobId("64b64f2f0000000000000001");
        f.setUploadedBy("u1");
        files.save(f);
        FileMetadata deleted = new FileMetadata("file-2", "two.owl", "p1", "w1");
        deleted.setStatus("DELETED");
        deleted.setIsDeleted(true);
        deleted.setDeletedAt(now().minusDays(31));
        files.save(deleted);

        FileMetadata loaded = files.findByFileId("file-1").orElseThrow();

        assertEquals("64b64f2f0000000000000001", loaded.getBlobId());
        assertEquals(123L, loaded.getFileSize());
        assertEquals(f.getId(), files.findById(f.getId()).orElseThrow().getId());
        assertEquals(2, files.findByProjectId("p1").size());
        assertEquals(1, files.findByProjectIdAndStatus("p1", "ACTIVE").size());
        assertEquals(2, files.findByWorkspaceId("w1").size());
        assertEquals(1, files.findByWorkspaceIdAndStatus("w1", "DELETED").size());
        assertEquals(1, files.findAllByIsDeletedTrueAndDeletedAtBefore(now().minusDays(30)).size());
        assertThrows(DuplicateKeyException.class, () -> files.save(new FileMetadata("file-1", "dup.owl", "p1", "w1")));

        files.deleteById(f.getId());
        files.deleteAll(files.findByProjectId("p1"));
        assertTrue(files.findByProjectId("p1").isEmpty());
    }

    @Test
    void invitationsAreFoundByTokenEmailWorkspaceAndStatusAndDeletedPerWorkspace() {
        Invitation a = new Invitation();
        a.setInvitationToken("tok-a");
        a.setInviteeEmail("Guest@Example.com");
        a.setWorkspaceId("w1");
        a.setWorkspaceName("W1");
        a.setInvitedBy("u1");
        a.setInvitedByEmail("owner@example.com");
        a.setRole("VIEWER");
        invitations.save(a);
        Invitation b = new Invitation();
        b.setInvitationToken("tok-b");
        b.setInviteeEmail("guest@example.com");
        b.setWorkspaceId("w2");
        b.setStatus("ACCEPTED");
        b.setAcceptedAt(now());
        invitations.save(b);

        assertEquals("tok-a", invitations.findByInvitationToken("tok-a").orElseThrow().getInvitationToken());
        assertTrue(invitations.existsByInvitationToken("tok-b"));
        assertFalse(invitations.existsByInvitationToken("tok-c"));
        assertEquals(1, invitations.findByWorkspaceIdAndStatus("w1", "PENDING").size());
        assertEquals("tok-a", invitations.findByInviteeEmailAndWorkspaceId("guest@EXAMPLE.com", "w1").orElseThrow().getInvitationToken());
        assertTrue(invitations.findByInviteeEmailAndWorkspaceIdAndStatus("guest@example.com", "w2", "PENDING").isEmpty());
        assertTrue(invitations.findByInviteeEmailAndWorkspaceIdAndStatus("guest@example.com", "w2", "ACCEPTED").isPresent());
        Invitation duplicateToken = new Invitation();
        duplicateToken.setInvitationToken("tok-a");
        assertThrows(DuplicateKeyException.class, () -> invitations.save(duplicateToken));

        invitations.deleteByWorkspaceId("w1");
        assertTrue(invitations.findByInvitationToken("tok-a").isEmpty());
        assertTrue(invitations.findByInvitationToken("tok-b").isPresent());
    }

    @Test
    void aStripeEventIsRecordedExactlyOnceUnderConcurrency() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        List<Boolean> results = inParallel(12, () -> {
            try {
                stripeEvents.insert(new StripeEvent("evt_1", "invoice.paid"));
                return true;
            } catch (DuplicateKeyException e) {
                counter.incrementAndGet();
                return false;
            }
        });

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(11, counter.get());
        assertTrue(stripeEvents.existsByStripeEventId("evt_1"));
    }

    @Test
    void aStripeEventStatusIsUpdatedAndFoundByTypeStatusAndTime() {
        StripeEvent e = new StripeEvent("evt_2", "invoice.payment_succeeded");
        e.setStatus("processing");
        stripeEvents.insert(e);
        e.setStatus("processed");
        e.setProcessedAt(now());
        stripeEvents.save(e);

        StripeEvent loaded = stripeEvents.findByStripeEventId("evt_2").orElseThrow();
        assertEquals("processed", loaded.getStatus());
        assertNotNull(loaded.getProcessedAt());
        assertEquals(1, stripeEvents.findByEventTypeAndStatusSince("invoice.payment_succeeded", "processed", now().minusHours(1)).size());
        assertTrue(stripeEvents.findByEventTypeAndStatusSince("invoice.payment_succeeded", "processed", now().plusHours(1)).isEmpty());
        assertTrue(stripeEvents.findByEventTypeAndStatusSince("other", "processed", now().minusHours(1)).isEmpty());
    }

    @Test
    void anEmailKeyIsReservedByExactlyOneCallerUnderConcurrency() throws Exception {
        EmailLog log = EmailLog.builder().key("billing-reminder:u1:2026-11-05").type("billing_reminder")
                .email("a@example.com").userId("u1").username("a").build();

        List<Boolean> results = inParallel(16, () -> emailLogs.reserve(log));

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, jdbc.queryForObject("select count(*) from email_log", Integer.class));
        assertEquals("sending", emailLogs.findByKey(log.key()).orElseThrow().status());
    }

    @Test
    void anEmailLogMovesFromSendingToSentOrFailedAndStatusChecksSeeIt() {
        EmailLog log = EmailLog.builder().key("k1").type("payment_receipt").email("a@example.com").userId("u1")
                .stripeInvoiceId("in_1").amountPaid(500L).currency("usd").daysBefore(7).kind("renewal")
                .periodEnd(now().plusDays(7)).build();
        assertTrue(emailLogs.reserve(log));
        assertFalse(emailLogs.reserve(log));
        assertTrue(emailLogs.existsWithStatus("k1", "sent", "sending"));
        assertFalse(emailLogs.existsWithStatus("k1", "sent"));

        emailLogs.markSent("k1", now());
        EmailLog sent = emailLogs.findByKey("k1").orElseThrow();
        assertEquals("sent", sent.status());
        assertNotNull(sent.sentAt());
        assertEquals(500L, sent.amountPaid());
        assertEquals(7, sent.daysBefore());
        assertTrue(emailLogs.existsWithStatus("k1", "sent"));

        assertTrue(emailLogs.reserve(EmailLog.builder().key("k2").type("t").build()));
        emailLogs.markFailed("k2", "smtp down", now());
        EmailLog failed = emailLogs.findByKey("k2").orElseThrow();
        assertEquals("failed", failed.status());
        assertEquals("smtp down", failed.errorMessage());
        assertFalse(emailLogs.existsWithStatus("k2", "sent", "sending"));
        assertTrue(emailLogs.findByKey("missing").isEmpty());
    }

    @Test
    void unmatchedInvoicesAreStoredAndListedNewestFirstWithinTheLimit() {
        for (int i = 0; i < 3; i++) {
            unmatched.insert(StripeUnmatchedInvoice.builder().invoiceId("in_" + i).amountPaid(100L * i)
                    .customerEmail("c@example.com").recordedAt(now().plusSeconds(i)).build());
        }

        List<StripeUnmatchedInvoice> recent = unmatched.findRecent(2);

        assertEquals(2, recent.size());
        assertEquals("in_2", recent.get(0).invoiceId());
        assertEquals("unmatched", recent.get(0).status());
        assertEquals(200L, recent.get(0).amountPaid());
    }

    @Test
    void auditEventsKeepTheirMetadataAsJson() {
        audit.insert("alice", "LOGIN_FAIL", "10.0.0.1", now(), Map.of("reason", "Bad credentials"));
        audit.insert("alice", "LOGIN_SUCCESS", null, now(), null);

        assertEquals("Bad credentials", jdbc.queryForObject(
                "select metadata ->> 'reason' from audit_event where event_type = 'LOGIN_FAIL'", String.class));
        assertEquals("{}", jdbc.queryForObject(
                "select metadata::text from audit_event where event_type = 'LOGIN_SUCCESS'", String.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from audit_event where username = 'alice'", Integer.class));
    }

    @Test
    void systemSettingsAreASingleRowWithListsAndTimes() {
        assertTrue(settings.findById(SystemSettings.GLOBAL_ID).isEmpty());
        SystemSettings s = new SystemSettings();
        s.setMaintenanceModeEnabled(true);
        s.setMaintenanceMessage("back soon");
        s.setMaintenanceAllowedDomains(List.of("a.com", "b.com"));
        s.setMaintenanceAllowedEmails(List.of("x@a.com"));
        s.setMaintenanceStartTime(now());
        s.setEnterpriseDomains(List.of("uni.edu"));
        s.setUpdatedBy("admin");
        s.setUpdatedAt(now());
        settings.save(s);
        s.setMaintenanceMessage("later");
        settings.save(s);

        SystemSettings loaded = settings.findById(SystemSettings.GLOBAL_ID).orElseThrow();

        assertEquals(1, jdbc.queryForObject("select count(*) from system_settings", Integer.class));
        assertEquals("later", loaded.getMaintenanceMessage());
        assertTrue(loaded.isMaintenanceModeEnabled());
        assertEquals(List.of("a.com", "b.com"), loaded.getMaintenanceAllowedDomains());
        assertEquals(List.of("x@a.com"), loaded.getMaintenanceAllowedEmails());
        assertEquals(List.of("uni.edu"), loaded.getEnterpriseDomains());
        assertEquals(s.getMaintenanceStartTime(), loaded.getMaintenanceStartTime());
        assertEquals("09:00", loaded.getMaintenanceDailyStartTime());
    }

    @Test
    void planConfigIsUpsertedByPlanIdEvenWhenSavedConcurrently() throws Exception {
        inParallel(8, () -> plans.save(new PlanFeatureConfig("PRO", 59, 10, List.of("a", "b"), List.of())));

        assertEquals(1, plans.findAll().size());
        PlanFeatureConfig stored = plans.findByPlanId("PRO").orElseThrow();
        assertEquals(59, stored.getMonthlyPrice());
        assertEquals(List.of("a", "b"), stored.getFeatures());
        assertTrue(stored.getLimitations().isEmpty());

        PlanFeatureConfig changed = plans.findByPlanId("PRO").orElseThrow();
        changed.setMonthlyPrice(79);
        changed.setMaxMembers(25);
        plans.save(changed);
        assertEquals(79, plans.findByPlanId("PRO").orElseThrow().getMonthlyPrice());
        assertEquals(25, plans.findByPlanId("PRO").orElseThrow().getMaxMembers());
        assertEquals(1, plans.findAll().size());
    }

    @Test
    void downloadEventsAreStoredAndCounted() {
        for (int i = 0; i < 3; i++) {
            DesktopDownloadEvent e = new DesktopDownloadEvent();
            e.setPlatform("windows-x64");
            e.setEventType(i == 0 ? "page_view" : "download");
            e.setIpHash("hash");
            downloads.save(e);
        }

        assertEquals(2, downloads.countByPlatformAndEventType("windows-x64", "download"));
        assertEquals(1, downloads.countByPlatformAndEventType("windows-x64", "page_view"));
        assertEquals(0, downloads.countByPlatformAndEventType("linux-x64", "download"));
    }

    @Test
    void renamingFollowsTheEditorTableWhenItExistsAndIsIgnoredWhenItDoesNot() {
        assertEquals(0, ontologyFileNames.updateFileName("p1--f1", "new.owl"));

        jdbc.execute("create table if not exists ontology_file (id text primary key, file_name text)");
        try {
            jdbc.update("delete from ontology_file");
            jdbc.update("insert into ontology_file(id, file_name) values ('p1--f1', 'old.owl')");

            assertEquals(1, ontologyFileNames.updateFileName("p1--f1", "new.owl"));
            assertEquals("new.owl", jdbc.queryForObject("select file_name from ontology_file where id = 'p1--f1'", String.class));
            assertEquals(0, ontologyFileNames.updateFileName("p1--other", "x.owl"));
        } finally {
            jdbc.execute("drop table ontology_file");
        }
    }

    @Test
    void purgingEditorDataRemovesRowsOfRegisteredTablesForThatProjectOnly() {
        jdbc.execute("create table if not exists auth_scoped_probe (id serial primary key, project_id text)");
        try {
            self.research.ontology.common.db.ProjectScopedTables.register("auth_scoped_probe");
            jdbc.update("delete from auth_scoped_probe");
            jdbc.update("insert into auth_scoped_probe(project_id) values ('p1'), ('p1'), ('p2')");

            assertEquals(2, purge.purgeEditorData("p1"));
            assertEquals(1, jdbc.queryForObject("select count(*) from auth_scoped_probe", Integer.class));
        } finally {
            jdbc.execute("drop table auth_scoped_probe");
        }
    }

    @Test
    void purgingEditorDataAlsoRemovesTheSwrlRulesOfThatProject() {
        jdbc.update("delete from swrl_rule where project_id in ('purge-p1', 'purge-p2')");
        jdbc.update("insert into swrl_rule(id, project_id, rule_name) values ('r1', 'purge-p1', 'a'), ('r2', 'purge-p1', 'b'), ('r3', 'purge-p2', 'a')");

        purge.purgeEditorData("purge-p1");

        assertEquals(0, jdbc.queryForObject("select count(*) from swrl_rule where project_id = 'purge-p1'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from swrl_rule where project_id = 'purge-p2'", Integer.class));
        jdbc.update("delete from swrl_rule where project_id = 'purge-p2'");
    }
}
