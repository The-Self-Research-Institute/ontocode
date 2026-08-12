package self.research.ontology.auth.controller;

import com.mongodb.client.gridfs.model.GridFSFile;
import jakarta.servlet.http.HttpServletRequest;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import self.research.ontology.auth.service.DesktopDownloadService;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves OntoCode Desktop installer files stored in GridFS.
 *
 * Upload (admin only):
 *   POST /api/downloads/upload?platform=windows-x64&version=1.0.1&releaseNotes=...
 *
 * Download (public — no auth required):
 *   GET  /api/downloads/windows-x64
 *
 * Version / update feed (public):
 *   GET  /api/downloads/info
 *   GET  /api/downloads/updates/win/latest.yml   (electron-updater generic provider)
 *
 * Analytics (public, privacy-friendly — IP stored as SHA-256 hash only):
 *   POST /api/downloads/track?platform=windows-x64&event=page_view&clientOs=macos
 *
 * List available (public):
 *   GET  /api/downloads
 */
@RestController
@RequestMapping("/api/downloads")
@CrossOrigin(originPatterns = "*")
public class DesktopDownloadController {

    private static final Logger log = LoggerFactory.getLogger(DesktopDownloadController.class);

    @Autowired
    private GridFsTemplate gridFsTemplate;

    @Autowired
    private DesktopDownloadService downloadService;

    @GetMapping("/info")
    public ResponseEntity<?> info() {
        return ResponseEntity.ok(downloadService.buildPublicInfo());
    }

    @GetMapping(value = "/updates/{channel}/latest.yml", produces = "text/yaml")
    public ResponseEntity<String> latestYml(@PathVariable String channel) {
        String yml = downloadService.buildLatestYml(channel);
        if (yml == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("version: 0.0.0\n");
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-cache")
            .body(yml);
    }

    @PostMapping("/track")
    public ResponseEntity<?> track(
            @RequestParam String platform,
            @RequestParam(defaultValue = "page_view") String event,
            @RequestParam(required = false) String clientOs,
            HttpServletRequest request) {
        GridFSFile file = downloadService.findInstaller(platform);
        downloadService.recordEvent(platform, event, request, file, clientOs);
        return ResponseEntity.ok(Map.of("recorded", true));
    }

    @GetMapping
    public ResponseEntity<?> listAvailable() {
        List<Map<String, String>> files = new ArrayList<>();
        gridFsTemplate.find(new Query(Criteria.where("metadata.bucket").is(DesktopDownloadService.BUCKET))).forEach(f -> {
            Map<String, String> entry = new LinkedHashMap<>();
            var meta = f.getMetadata();
            entry.put("platform", meta != null ? String.valueOf(meta.get("platform")) : "");
            entry.put("filename", f.getFilename());
            entry.put("size", String.valueOf(f.getLength()));
            if (meta != null && meta.get("version") != null) {
                entry.put("version", String.valueOf(meta.get("version")));
            }
            files.add(entry);
        });
        return ResponseEntity.ok(Map.of("available", files));
    }

    @GetMapping("/{platform}")
    public ResponseEntity<?> download(
            @PathVariable String platform,
            @RequestParam(required = false) String clientOs,
            HttpServletRequest request) {
        if ("info".equals(platform) || platform.startsWith("updates")) {
            return ResponseEntity.notFound().build();
        }
        try {
            GridFSFile file = downloadService.findInstaller(platform);
            if (file == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Installer for platform '" + platform + "' not yet available",
                                 "platform", platform));
            }

            downloadService.recordEvent(platform, "download", request, file, clientOs);

            GridFsResource resource = gridFsTemplate.getResource(file);
            String filename = file.getFilename();
            String contentType = detectContentType(filename);
            long contentLength = file.getLength();

            // Advertise + honor byte-range requests so download managers (aria2, browsers,
            // electron-updater) can resume and parallelize instead of falling back to one
            // slow connection. GridFsResource's InputStream supports efficient seeking
            // (GridFSDownloadStream.skip is chunk-aware), so this isn't a naive read-and-discard.
            String rangeHeader = request.getHeader(HttpHeaders.RANGE);
            if (rangeHeader == null) {
                return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength))
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(new InputStreamResource(resource.getInputStream()));
            }

            List<HttpRange> ranges;
            try {
                ranges = HttpRange.parseRanges(rangeHeader);
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + contentLength)
                    .build();
            }
            if (ranges.isEmpty()) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + contentLength)
                    .build();
            }

            // Only the first range is honored — download managers issue one range per request
            // rather than a single multi-range request, so this covers the real-world case.
            ResourceRegion region = ranges.get(0).toResourceRegion(resource);
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(MediaType.parseMediaType(contentType))
                .body(region);

        } catch (Exception e) {
            log.error("Download failed for platform {}: {}", platform, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Download failed: " + e.getMessage()));
        }
    }

    @PostMapping("/upload")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<?> upload(
            @RequestParam String platform,
            @RequestParam String filename,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String releaseNotes,
            @RequestParam("file") MultipartFile file) {
        try {
            gridFsTemplate.delete(new Query(
                Criteria.where("metadata.platform").is(platform)
                        .and("metadata.bucket").is(DesktopDownloadService.BUCKET)
            ));

            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            String resolvedVersion = version != null && !version.isBlank() ? version.trim() : "1.0.0";
            org.bson.Document metadata = new org.bson.Document();
            metadata.put("platform", platform);
            metadata.put("bucket", DesktopDownloadService.BUCKET);
            metadata.put("originalName", file.getOriginalFilename());
            metadata.put("version", resolvedVersion);
            metadata.put("releaseNotes", releaseNotes != null ? releaseNotes : "");
            metadata.put("publishedAt", Instant.now().toString());

            Path tempFile = Files.createTempFile("ontocode-installer-", "-" + filename);
            String sha512Base64;
            try (InputStream raw = file.getInputStream();
                 DigestInputStream dis = new DigestInputStream(raw, sha512)) {
                Files.copy(dis, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                sha512Base64 = Base64.getEncoder().encodeToString(sha512.digest());
            }
            metadata.put("sha512", sha512Base64);

            ObjectId id;
            try (InputStream raw = Files.newInputStream(tempFile)) {
                id = gridFsTemplate.store(raw, filename,
                    detectContentType(filename), metadata);
            } finally {
                Files.deleteIfExists(tempFile);
            }
            log.info("[DesktopDownload] Uploaded {} v{} for platform {} — id={}", filename,
                metadata.get("version"), platform, id);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "platform", platform,
                "filename", filename,
                "version", metadata.get("version"),
                "sha512", sha512Base64,
                "id", id.toHexString(),
                "downloadUrl", "/api/downloads/" + platform
            ));
        } catch (Exception e) {
            log.error("Upload failed for platform {}: {}", platform, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{platform}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<?> delete(@PathVariable String platform) {
        gridFsTemplate.delete(new Query(
            Criteria.where("metadata.platform").is(platform)
                    .and("metadata.bucket").is(DesktopDownloadService.BUCKET)
        ));
        return ResponseEntity.ok(Map.of("success", true, "deleted", platform));
    }

    private String detectContentType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".exe")) return "application/x-msdownload";
        if (lower.endsWith(".dmg")) return "application/x-apple-diskimage";
        if (lower.endsWith(".appimage")) return "application/x-executable";
        if (lower.endsWith(".deb")) return "application/vnd.debian.binary-package";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "text/yaml";
        return "application/octet-stream";
    }
}
