package self.research.ontology.auth.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;

import java.time.LocalDateTime;
import java.util.Map;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcAuditEventRepository implements AuditEventRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcAuditEventRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void insert(String username, String eventType, String ipAddress, LocalDateTime timestamp,
                       Map<String, String> metadata) {
        String json;
        try {
            json = mapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Audit metadata cannot be written", e);
        }
        AuthSql.update(jdbc, "insert into audit_event (id, username, event_type, ip_address, event_time, metadata) "
                + "values (?, ?, ?, ?, ?, ?::jsonb)", Ids.newObjectId(), username, eventType, ipAddress,
                AuthSql.db(timestamp), json);
    }
}
