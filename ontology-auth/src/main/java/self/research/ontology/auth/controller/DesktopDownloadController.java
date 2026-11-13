package self.research.ontology.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import self.research.ontology.auth.service.DesktopDownloadService;
import self.research.ontology.common.db.StoredFile;

import java.io.InputStream;
import java.io.OutputStream;
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

@RestController
@RequestMapping("/api/downloads")
@CrossOrigin(originPatterns = "*")
public class DesktopDownloadController {

    private static final Logger log = LoggerFactory.getLogger(DesktopDownloadController.class);

    private final java.util.concurrent.ConcurrentHashMap<String, Object> uploadLocks =
        new java.util.concurrent.ConcurrentHashMap<>();

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
        StoredFile file = downloadService.findInstaller(platform);
        downloadService.recordEvent(platform, event, request, file, clientOs);
        return ResponseEntity.ok(Map.of("recorded", true));
    }

    @GetMapping
    public ResponseEntity<?> listAvailable() {
        List<Map<String, String>> files = new ArrayList<>();
        downloadService.listInstallers().forEach(f -> {
            Map<String, String> entry = new LinkedHashMap<>();
            var meta = f.metadata();
            entry.put("platform", meta != null ? String.valueOf(meta.get("platform")) : "");
            entry.put("filename", f.filename());
            entry.put("size", String.valueOf(f.length()));
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
            HttpServletRequest request,
            HttpServletResponse response) {
        if ("info".equals(platform) || platform.startsWith("updates")) {
            return ResponseEntity.notFound().build();
        }
        try {
            StoredFile file = downloadService.findInstaller(platform);
            if (file == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Installer for platform '" + platform + "' not yet available",
                                 "platform", platform));
            }

            downloadService.recordEvent(platform, "download", request, file, clientOs);

            String filename = file.filename();
            String contentType = detectContentType(filename);
            long contentLength = file.length();

            String rangeHeader = request.getHeader(HttpHeaders.RANGE);
            if (rangeHeader == null) {
                return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength))
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(new InputStreamResource(downloadService.openInstaller(file)));
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

            HttpRange range = ranges.get(0);
            long start = range.getRangeStart(contentLength);
            long end = range.getRangeEnd(contentLength);
            if (start < 0 || end < start || end >= contentLength) {
                response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + contentLength);
                return null;
            }
            long rangeLength = end - start + 1;

            response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + contentLength);
            response.setContentLengthLong(rangeLength);
            response.setContentType(contentType);

            try (InputStream in = downloadService.openInstaller(file)) {
                skipFully(in, start);
                OutputStream out = response.getOutputStream();
                byte[] buffer = new byte[8192];
                long remaining = rangeLength;
                while (remaining > 0) {
                    int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) break;
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
                out.flush();
            }
            return null;

        } catch (Exception e) {
            log.error("Download failed for platform {}: {}", platform, e.getMessage());
            if (!response.isCommitted()) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Download failed: " + e.getMessage()));
            }
            return null;
        }
    }

    private static void skipFully(InputStream in, long toSkip) throws java.io.IOException {
        while (toSkip > 0) {
            long skipped = in.skip(toSkip);
            if (skipped > 0) {
                toSkip -= skipped;
            } else if (in.read() < 0) {
                break;
            } else {
                toSkip--;
            }
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
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            String resolvedVersion = version != null && !version.isBlank() ? version.trim() : "1.0.0";
            Map<String, Object> metadata = new LinkedHashMap<>();
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

            String id;
            Object lock = uploadLocks.computeIfAbsent(platform, k -> new Object());
            synchronized (lock) {
                try (InputStream raw = Files.newInputStream(tempFile)) {
                    id = downloadService.replaceInstaller(platform, filename,
                        detectContentType(filename), metadata, raw).id();
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            }
            log.info("[DesktopDownload] Uploaded {} v{} for platform {} — id={}", filename,
                metadata.get("version"), platform, id);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "platform", platform,
                "filename", filename,
                "version", metadata.get("version"),
                "sha512", sha512Base64,
                "id", id,
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
        Object lock = uploadLocks.computeIfAbsent(platform, k -> new Object());
        synchronized (lock) {
            downloadService.deleteInstallers(platform);
        }
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
