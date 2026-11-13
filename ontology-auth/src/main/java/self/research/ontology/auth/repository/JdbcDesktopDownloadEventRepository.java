package self.research.ontology.auth.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import self.research.ontology.auth.model.DesktopDownloadEvent;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcDesktopDownloadEventRepository implements DesktopDownloadEventRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("desktop_download_event")
            .key("id")
            .columns("id", "platform", "client_os", "version", "filename", "ip_hash", "user_agent", "referer",
                    "event_type", "recorded_at")
            .build();

    private final JdbcTemplate jdbc;

    public JdbcDesktopDownloadEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DesktopDownloadEvent save(DesktopDownloadEvent e) {
        e.setId(AuthSql.idOrNew(e.getId()));
        AuthSql.update(jdbc, UPSERT.sql(), e.getId(), e.getPlatform(), e.getClientOs(), e.getVersion(),
                e.getFilename(), e.getIpHash(), e.getUserAgent(), e.getReferer(), e.getEventType(),
                Instants.toDb(e.getRecordedAt()));
        return e;
    }

    @Override
    public long countByPlatformAndEventType(String platform, String eventType) {
        Long count = jdbc.queryForObject(
                "select count(*) from desktop_download_event where platform = ? and event_type = ?", Long.class,
                platform, eventType);
        return count == null ? 0 : count;
    }
}
