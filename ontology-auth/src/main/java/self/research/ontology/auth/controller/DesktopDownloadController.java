package self.research.ontology.auth.controller;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.InputStreamResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Serves OntoCode Desktop installer files stored in GridFS.
 *
 * Upload (admin only):
 *   POST /api/downloads/upload?platform=windows-x64   (multipart file)
 *
 * Download (public — no auth required):
 *   GET  /api/downloads/windows-x64    → OntoCode-Setup-x64.exe
 *   GET  /api/downloads/windows-arm64  → OntoCode-Setup-arm64.exe
 *   GET  /api/downloads/mac-arm64      → OntoCode-arm64.dmg
 *   GET  /api/downloads/mac-x64        → OntoCode-x64.dmg
 *   GET  /api/downloads/linux-x64      → OntoCode-x86_64.AppImage
 *   GET  /api/downloads/linux-deb      → ontocode_amd64.deb
 *
 * List available (public):
 *   GET  /api/downloads
 */
@RestController
@RequestMapping("/api/downloads")
@CrossOrigin(originPatterns = "*")
public class DesktopDownloadController {

    private static final Logger log = LoggerFactory.getLogger(DesktopDownloadController.class);
    private static final String BUCKET = "desktop-installers";

    @Autowired
    private GridFsTemplate gridFsTemplate;

    // ── Public: list available downloads ─────────────────────────────────────

    @GetMapping
    public ResponseEntity<?> listAvailable() {
        List<Map<String, String>> files = new ArrayList<>();
        gridFsTemplate.find(new Query(Criteria.where("metadata.bucket").is(BUCKET))).forEach(f -> {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("platform", f.getMetadata() != null ? (String) f.getMetadata().get("platform") : "");
            entry.put("filename", f.getFilename());
            entry.put("size", String.valueOf(f.getLength()));
            files.add(entry);
        });
        return ResponseEntity.ok(Map.of("available", files));
    }

    // ── Public: download installer ────────────────────────────────────────────

    @GetMapping("/{platform}")
    public ResponseEntity<?> download(@PathVariable String platform) {
        try {
            GridFSFile file = gridFsTemplate.findOne(
                new Query(Criteria.where("metadata.platform").is(platform)
                                  .and("metadata.bucket").is(BUCKET))
            );
            if (file == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Installer for platform '" + platform + "' not yet available",
                                 "platform", platform));
            }

            GridFsResource resource = gridFsTemplate.getResource(file);
            String filename = file.getFilename();
            String contentType = detectContentType(filename);

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(file.getLength()))
                .contentType(MediaType.parseMediaType(contentType))
                .body(new InputStreamResource(resource.getInputStream()));

        } catch (Exception e) {
            log.error("Download failed for platform {}: {}", platform, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Download failed: " + e.getMessage()));
        }
    }

    // ── Admin: upload installer ───────────────────────────────────────────────

    @PostMapping("/upload")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<?> upload(
            @RequestParam String platform,
            @RequestParam String filename,
            @RequestParam("file") MultipartFile file) {
        try {
            // Delete existing for this platform
            gridFsTemplate.delete(new Query(
                Criteria.where("metadata.platform").is(platform)
                        .and("metadata.bucket").is(BUCKET)
            ));

            org.bson.Document metadata = new org.bson.Document();
            metadata.put("platform", platform);
            metadata.put("bucket", BUCKET);
            metadata.put("originalName", file.getOriginalFilename());

            try (InputStream is = file.getInputStream()) {
                ObjectId id = gridFsTemplate.store(is, filename,
                    detectContentType(filename), metadata);
                log.info("[DesktopDownload] Uploaded {} for platform {} — id={}", filename, platform, id);
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "platform", platform,
                    "filename", filename,
                    "id", id.toHexString(),
                    "downloadUrl", "/api/downloads/" + platform
                ));
            }
        } catch (Exception e) {
            log.error("Upload failed for platform {}: {}", platform, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }

    // ── Admin: delete installer ───────────────────────────────────────────────

    @DeleteMapping("/{platform}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<?> delete(@PathVariable String platform) {
        gridFsTemplate.delete(new Query(
            Criteria.where("metadata.platform").is(platform)
                    .and("metadata.bucket").is(BUCKET)
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
        return "application/octet-stream";
    }
}
