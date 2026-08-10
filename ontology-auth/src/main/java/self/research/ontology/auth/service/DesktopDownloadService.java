package self.research.ontology.auth.service;

import com.mongodb.client.gridfs.model.GridFSFile;
import jakarta.servlet.http.HttpServletRequest;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import self.research.ontology.auth.model.DesktopDownloadEvent;
import self.research.ontology.auth.repository.DesktopDownloadEventRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class DesktopDownloadService {

    private static final Logger log = LoggerFactory.getLogger(DesktopDownloadService.class);
    public static final String BUCKET = "desktop-installers";
    private static final String WINDOWS_PLATFORM = "windows-x64";
    private static final String[] KNOWN_PLATFORMS = {
        WINDOWS_PLATFORM, "linux-x64", "linux-deb", "linux-arm64", "linux-flatpak", "mac-arm64",
    };

    @Value("${ontocode.downloads.tracking-salt:ontocode-download-tracking}")
    private String trackingSalt;

    @Value("${ontocode.downloads.update-base-url:https://ontocodeapi.selfresearch.org/api/downloads}")
    private String updateBaseUrl;

    @Autowired
    private GridFsTemplate gridFsTemplate;

    @Autowired
    private DesktopDownloadEventRepository eventRepository;

    public GridFSFile findInstaller(String platform) {
        return gridFsTemplate.findOne(
            new Query(Criteria.where("metadata.platform").is(platform)
                              .and("metadata.bucket").is(BUCKET))
        );
    }

    public Map<String, Object> buildPublicInfo() {
        Map<String, Object> latest = new LinkedHashMap<>();
        java.util.List<String> availablePlatforms = new java.util.ArrayList<>();
        for (String platform : KNOWN_PLATFORMS) {
            GridFSFile file = findInstaller(platform);
            if (file == null) continue;
            Document meta = file.getMetadata();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("version", metaString(meta, "version", "1.0.0"));
            entry.put("filename", file.getFilename());
            entry.put("size", file.getLength());
            entry.put("releaseNotes", metaString(meta, "releaseNotes", ""));
            entry.put("publishedAt", metaString(meta, "publishedAt", ""));
            entry.put("downloadUrl", "/api/downloads/" + platform);
            latest.put(platform, entry);
            availablePlatforms.add(platform);
        }

        Map<String, Object> requirements = new LinkedHashMap<>();
        requirements.put("os", "Windows 10+, Ubuntu 20.04+ (or equivalent), macOS 12+");
        requirements.put("ram", "8 GB minimum · 16 GB recommended");
        requirements.put("disk", "2 GB free disk space for app and local data");
        requirements.put("display", "1280×720 minimum resolution");
        requirements.put("java", "Bundled — no separate Java install required");
        requirements.put("network", "Optional — full offline editing supported");

        return Map.of(
            "latest", latest,
            "systemRequirements", requirements,
            "supportedPlatforms", availablePlatforms.toArray(new String[0])
        );
    }

    public String buildLatestYml(String channel) {
        if (!"win".equalsIgnoreCase(channel) && !"windows".equalsIgnoreCase(channel)) {
            return null;
        }
        GridFSFile file = findInstaller(WINDOWS_PLATFORM);
        if (file == null) return null;

        Document meta = file.getMetadata();
        String version = metaString(meta, "version", "1.0.0");
        String sha512 = metaString(meta, "sha512", "");
        String filename = file.getFilename();
        long size = file.getLength();
        String releaseDate = metaString(meta, "publishedAt", Instant.now().toString());
        if (!releaseDate.endsWith("Z") && !releaseDate.contains("+")) {
            releaseDate = Instant.parse(releaseDate).atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }

        String fileUrl = updateBaseUrl + "/" + WINDOWS_PLATFORM;
        // electron-updater names the cached file from the URL path segment (windows-x64),
        // not Content-Disposition — path must match or quitAndInstall prompts for the exe.
        String cachedPath = WINDOWS_PLATFORM;
        StringBuilder yml = new StringBuilder();
        yml.append("version: ").append(version).append('\n');
        yml.append("files:\n");
        yml.append("  - url: ").append(yamlQuote(fileUrl)).append('\n');
        if (!sha512.isBlank()) {
            yml.append("    sha512: ").append(sha512).append('\n');
        }
        yml.append("    size: ").append(size).append('\n');
        yml.append("path: ").append(yamlQuote(cachedPath)).append('\n');
        if (!sha512.isBlank()) {
            yml.append("sha512: ").append(sha512).append('\n');
        }
        yml.append("releaseDate: ").append(yamlQuote(releaseDate)).append('\n');
        return yml.toString();
    }

    @Async
    public void recordEvent(String platform, String eventType, HttpServletRequest request,
                            GridFSFile file, String clientOsHint) {
        try {
            DesktopDownloadEvent event = new DesktopDownloadEvent();
            event.setPlatform(platform);
            event.setClientOs(resolveClientOs(clientOsHint, request.getHeader("User-Agent")));
            event.setEventType(eventType);
            event.setIpHash(hashClientIp(resolveClientIp(request)));
            event.setUserAgent(truncate(request.getHeader("User-Agent"), 512));
            event.setReferer(truncate(request.getHeader("Referer"), 512));
            if (file != null) {
                event.setFilename(file.getFilename());
                Document meta = file.getMetadata();
                if (meta != null) {
                    event.setVersion(metaString(meta, "version", null));
                }
            }
            eventRepository.save(event);
        } catch (Exception e) {
            log.warn("[DesktopDownload] Failed to record {} for {}: {}", eventType, platform, e.getMessage());
        }
    }

    public String hashClientIp(String ip) {
        if (ip == null || ip.isBlank()) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((trackingSalt + "|" + ip.trim()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "";
        }
    }

    static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return realIp.trim();
        return request.getRemoteAddr();
    }

    static String resolveClientOs(String clientOsHint, String userAgent) {
        if (clientOsHint != null && !clientOsHint.isBlank()) {
            return normalizeClientOs(clientOsHint);
        }
        return detectClientOsFromUserAgent(userAgent);
    }

    static String normalizeClientOs(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return switch (value.trim().toLowerCase()) {
            case "win", "windows", "windows-x64", "windows-arm64" -> "windows";
            case "mac", "macos", "osx", "darwin", "mac-arm64", "mac-x64" -> "macos";
            case "linux", "ubuntu", "debian", "linux-x64", "linux-deb", "linux-arm64", "linux-flatpak" -> "linux";
            case "android" -> "android";
            case "ios", "iphone", "ipad" -> "ios";
            default -> value.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "");
        };
    }

    static String detectClientOsFromUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "unknown";
        String ua = userAgent.toLowerCase();
        if (ua.contains("android")) return "android";
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod")) return "ios";
        if (ua.contains("mac os x") || ua.contains("macintosh")) return "macos";
        if (ua.contains("windows") || ua.contains("win32") || ua.contains("win64")) return "windows";
        if (ua.contains("linux") || ua.contains("x11") || ua.contains("ubuntu") || ua.contains("debian")) {
            return "linux";
        }
        return "unknown";
    }

    private static String metaString(Document meta, String key, String fallback) {
        if (meta == null) return fallback;
        Object val = meta.get(key);
        return val != null ? String.valueOf(val) : fallback;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String yamlQuote(String value) {
        if (value == null) return "''";
        if (value.matches("^[\\w./:-]+$")) return value;
        return "'" + value.replace("'", "''") + "'";
    }
}
