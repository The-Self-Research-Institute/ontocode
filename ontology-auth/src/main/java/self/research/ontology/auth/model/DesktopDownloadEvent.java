package self.research.ontology.auth.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Privacy-friendly download analytics — stores a one-way hash of the client IP
 * (never the raw address) plus coarse request metadata.
 */
@Document(collection = "desktop_download_events")
public class DesktopDownloadEvent {

    @Id
    private String id;

    @Indexed
    private String platform;

    /** Visitor OS (windows, macos, linux, android, ios, unknown) — not the installer platform. */
    @Indexed
    private String clientOs;

    private String version;
    private String filename;
    private String ipHash;
    private String userAgent;
    private String referer;
    private String eventType; // download | page_view
    private Instant recordedAt;

    public DesktopDownloadEvent() {
        this.recordedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public String getClientOs() { return clientOs; }
    public void setClientOs(String clientOs) { this.clientOs = clientOs; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getIpHash() { return ipHash; }
    public void setIpHash(String ipHash) { this.ipHash = ipHash; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public String getReferer() { return referer; }
    public void setReferer(String referer) { this.referer = referer; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
}
