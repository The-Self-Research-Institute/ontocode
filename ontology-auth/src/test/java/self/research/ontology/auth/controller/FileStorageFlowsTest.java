package self.research.ontology.auth.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import self.research.ontology.auth.EmbeddedDatabaseSpringTest;
import self.research.ontology.auth.model.FileMetadata;
import self.research.ontology.auth.model.Project;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.FileMetadataRepository;
import self.research.ontology.auth.repository.ProjectRepository;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.service.DesktopDownloadService;
import self.research.ontology.auth.service.ProjectService;
import self.research.ontology.auth.service.WorkspaceService;
import self.research.ontology.common.db.BlobStore;
import self.research.ontology.common.db.StoredFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest
class FileStorageFlowsTest extends EmbeddedDatabaseSpringTest {

    @Autowired
    ProjectController projectController;

    @Autowired
    DesktopDownloadController downloadController;

    @Autowired
    DesktopDownloadService downloadService;

    @Autowired
    ProjectService projectService;

    @Autowired
    WorkspaceService workspaceService;

    @Autowired
    UserRepository users;

    @Autowired
    ProjectRepository projects;

    @Autowired
    FileMetadataRepository fileMetadata;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    BlobStore blobStore;

    User owner;
    Project project;

    @BeforeEach
    void setUp() {
        Mockito.reset(blobStore);
        for (String table : List.of("project", "workspace", "file_metadata", "app_user", "stored_file")) {
            jdbc.update("delete from " + table);
        }
        User u = new User();
        u.setUsername("uploader");
        u.setEmail("uploader@example.com");
        u.setPassword("hash");
        u.setEnabled(true);
        u.setRoles(Set.of("ROLE_USER"));
        owner = users.save(u);
        Workspace w = workspaceService.createWorkspace(owner.getId(), "Files", null);
        project = projectService.createProject(w.getWorkspaceId(), owner.getId(), owner.getUsername(), owner.getEmail(), "Files", null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                owner.getEmail(), null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static MockMultipartFile upload(String name, String content) {
        return new MockMultipartFile("file", name, "application/rdf+xml", content.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private String uploadFile(String name, String content, String replaceFileId) {
        ResponseEntity<?> response = projectController.uploadFile(project.getProjectId(), upload(name, content), name,
                replaceFileId, "application/rdf+xml");
        assertEquals(200, response.getStatusCode().value(), String.valueOf(response.getBody()));
        return (String) ((Map<String, Object>) response.getBody()).get("fileId");
    }

    private byte[] read(String blobId) throws Exception {
        try (InputStream in = blobStore.open(blobId)) {
            return in.readAllBytes();
        }
    }

    @Test
    void anUploadedFileIsStoredInTheFsBucketWithItsRecordAndComesBackThroughTheContentEndpoint() throws Exception {
        String fileId = uploadFile("pizza.owl", "<rdf>pizza</rdf>", null);

        FileMetadata meta = fileMetadata.findByFileId(fileId).orElseThrow();
        StoredFile stored = blobStore.info(meta.getBlobId()).orElseThrow();
        assertEquals(24, meta.getBlobId().length());
        assertEquals("fs", stored.bucket());
        assertEquals("pizza.owl", stored.filename());
        assertEquals(project.getProjectId(), stored.metadata().get("projectId"));
        assertArrayEquals("<rdf>pizza</rdf>".getBytes(StandardCharsets.UTF_8), read(meta.getBlobId()));
        assertEquals(1, projects.findByProjectId(project.getProjectId()).orElseThrow().getActiveFiles().size());

        MockHttpServletResponse response = new MockHttpServletResponse();
        projectController.getFileContent(project.getProjectId(), fileId, response);
        String json = response.getContentAsString();
        assertTrue(json.contains("\"name\":\"pizza.owl\""));
        assertTrue(json.contains(Base64.getEncoder().encodeToString("<rdf>pizza</rdf>".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void replacingAFileStoresTheNewOneFirstThenRemovesTheOldRecordAndFile() throws Exception {
        String oldId = uploadFile("v1.owl", "version one", null);
        String oldBlob = fileMetadata.findByFileId(oldId).orElseThrow().getBlobId();

        String newId = uploadFile("v2.owl", "version two", oldId);

        assertNotEquals(oldId, newId);
        assertTrue(fileMetadata.findByFileId(oldId).isEmpty());
        assertTrue(blobStore.info(oldBlob).isEmpty());
        FileMetadata fresh = fileMetadata.findByFileId(newId).orElseThrow();
        assertArrayEquals("version two".getBytes(StandardCharsets.UTF_8), read(fresh.getBlobId()));
        Project loaded = projects.findByProjectId(project.getProjectId()).orElseThrow();
        assertEquals(List.of(newId), loaded.getActiveFiles().stream().map(Project.FileMetadataInfo::getFileId).toList());
        assertEquals("DELETED", loaded.getFiles().stream().filter(f -> f.getFileId().equals(oldId)).findFirst().orElseThrow().getStatus());
        assertEquals(1, jdbc.queryForObject("select count(*) from stored_file where bucket = 'fs'", Integer.class));
    }

    @Test
    void aFailedStoreDuringReplaceLeavesTheOldFileAndRecordUntouched() throws Exception {
        String oldId = uploadFile("v1.owl", "version one", null);
        String oldBlob = fileMetadata.findByFileId(oldId).orElseThrow().getBlobId();
        Mockito.doThrow(new IllegalStateException("disk full")).when(blobStore).store(any(), any(), any(), any(), any());

        ResponseEntity<?> response = projectController.uploadFile(project.getProjectId(), upload("v2.owl", "version two"),
                "v2.owl", oldId, "application/rdf+xml");

        assertEquals(500, response.getStatusCode().value());
        Mockito.reset(blobStore);
        assertTrue(fileMetadata.findByFileId(oldId).isPresent());
        assertArrayEquals("version one".getBytes(StandardCharsets.UTF_8), read(oldBlob));
        Project loaded = projects.findByProjectId(project.getProjectId()).orElseThrow();
        assertEquals(1, loaded.getActiveFiles().size());
        assertEquals(oldId, loaded.getActiveFiles().get(0).getFileId());
    }

    @Test
    void deletingAFileMarksItDeletedAndRemovesTheStoredFile() throws Exception {
        String fileId = uploadFile("gone.owl", "content", null);
        String blobId = fileMetadata.findByFileId(fileId).orElseThrow().getBlobId();

        ResponseEntity<?> response = projectController.deleteFile(project.getProjectId(), fileId);

        assertEquals(200, response.getStatusCode().value());
        FileMetadata meta = fileMetadata.findByFileId(fileId).orElseThrow();
        assertTrue(meta.getIsDeleted());
        assertEquals("DELETED", meta.getStatus());
        assertTrue(blobStore.info(blobId).isEmpty());
        assertTrue(projects.findByProjectId(project.getProjectId()).orElseThrow().getActiveFiles().isEmpty());
    }

    @Test
    void renamingAFileUpdatesTheProjectAndTheFileRecord() {
        String fileId = uploadFile("before.owl", "content", null);

        ResponseEntity<?> response = projectController.renameFile(project.getProjectId(), fileId, Map.of("fileName", "after"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("after.owl", fileMetadata.findByFileId(fileId).orElseThrow().getFileName());
        assertEquals("after.owl", projects.findByProjectId(project.getProjectId()).orElseThrow().getFile(fileId).getFileName());
    }

    @Test
    void purgingAProjectRemovesItsStoredFilesAndFileRecords() throws Exception {
        String fileId = uploadFile("purge.owl", "content", null);
        String blobId = fileMetadata.findByFileId(fileId).orElseThrow().getBlobId();

        projectService.hardDeleteProjectCompletely(project.getProjectId(), owner.getId());

        assertTrue(blobStore.info(blobId).isEmpty());
        assertTrue(fileMetadata.findByProjectId(project.getProjectId()).isEmpty());
        assertTrue(projects.findByProjectId(project.getProjectId()).isEmpty());
    }

    private StoredFile installer(String platform, String filename, String content, String version) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("platform", platform);
        metadata.put("bucket", DesktopDownloadService.BUCKET);
        metadata.put("version", version);
        metadata.put("releaseNotes", "notes");
        metadata.put("publishedAt", java.time.Instant.now().toString());
        metadata.put("sha512", "abc==");
        return downloadService.replaceInstaller(platform, filename, "application/x-msdownload", metadata,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void anInstallerUploadReplacesThePlatformsInstallerAfterTheNewOneIsStored() throws Exception {
        StoredFile first = installer("windows-x64", "setup-1.exe", "first build", "1.0.0");
        StoredFile second = installer("windows-x64", "setup-2.exe", "second build!", "1.1.0");
        installer("linux-x64", "app.AppImage", "linux", "1.1.0");

        assertNotEquals(first.id(), second.id());
        assertTrue(blobStore.info(first.id()).isEmpty());
        StoredFile current = downloadService.findInstaller("windows-x64");
        assertEquals(second.id(), current.id());
        assertEquals("setup-2.exe", current.filename());
        assertEquals("1.1.0", current.metadata().get("version"));
        assertEquals(2, downloadService.listInstallers().size());
        assertEquals(1, blobStore.find(DesktopDownloadService.BUCKET, null, Map.of("platform", "windows-x64")).size());
        String yml = downloadService.buildLatestYml("win");
        assertTrue(yml.contains("version: 1.1.0"));
        assertTrue(yml.contains("sha512: abc=="));
        assertTrue(yml.contains("size: 13"));
    }

    @Test
    void aFailedInstallerStoreKeepsThePreviousInstaller() throws Exception {
        StoredFile first = installer("windows-x64", "setup-1.exe", "first build", "1.0.0");
        Mockito.doThrow(new IllegalStateException("disk full")).when(blobStore).store(any(), any(), any(), any(), any());

        try {
            installer("windows-x64", "setup-2.exe", "second build", "1.1.0");
            org.junit.jupiter.api.Assertions.fail("the store failure should reach the caller");
        } catch (IllegalStateException expected) {
            assertEquals("disk full", expected.getMessage());
        }
        Mockito.reset(blobStore);

        assertEquals(first.id(), downloadService.findInstaller("windows-x64").id());
        assertArrayEquals("first build".getBytes(StandardCharsets.UTF_8), read(first.id()));
    }

    @Test
    void installersAreServedWholeOrByRangeAndCanBeDeleted() throws Exception {
        installer("windows-x64", "setup.exe", "0123456789", "1.0.0");

        MockHttpServletRequest whole = new MockHttpServletRequest();
        MockHttpServletResponse wholeResponse = new MockHttpServletResponse();
        ResponseEntity<?> body = downloadController.download("windows-x64", null, whole, wholeResponse);
        assertEquals(200, body.getStatusCode().value());
        assertEquals("10", body.getHeaders().getFirst("Content-Length"));
        assertEquals("bytes", body.getHeaders().getFirst("Accept-Ranges"));
        try (InputStream in = ((org.springframework.core.io.InputStreamResource) body.getBody()).getInputStream()) {
            assertEquals("0123456789", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        MockHttpServletRequest ranged = new MockHttpServletRequest();
        ranged.addHeader("Range", "bytes=2-5");
        MockHttpServletResponse rangedResponse = new MockHttpServletResponse();
        assertNull(downloadController.download("windows-x64", null, ranged, rangedResponse));
        assertEquals(206, rangedResponse.getStatus());
        assertEquals("2345", rangedResponse.getContentAsString());
        assertEquals("bytes 2-5/10", rangedResponse.getHeader("Content-Range"));

        MockHttpServletRequest outOfRange = new MockHttpServletRequest();
        outOfRange.addHeader("Range", "bytes=20-30");
        MockHttpServletResponse outOfRangeResponse = new MockHttpServletResponse();
        downloadController.download("windows-x64", null, outOfRange, outOfRangeResponse);
        assertEquals(416, outOfRangeResponse.getStatus());

        assertEquals(404, downloadController.download("mac-arm64", null, whole, new MockHttpServletResponse()).getStatusCode().value());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        downloadController.delete("windows-x64");
        assertEquals(404, downloadController.download("windows-x64", null, whole, new MockHttpServletResponse()).getStatusCode().value());
        assertNull(downloadService.findInstaller("windows-x64"));
    }
}
