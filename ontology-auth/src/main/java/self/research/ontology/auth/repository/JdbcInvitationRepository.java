package self.research.ontology.auth.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.auth.model.Invitation;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;

import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcInvitationRepository implements InvitationRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("invitation")
            .key("id")
            .columns("id", "invitation_token", "invitee_email", "workspace_id", "workspace_name", "invited_by",
                    "invited_by_email", "role", "status", "created_at", "accepted_at")
            .build();

    private static final RowMapper<Invitation> ROWS = (rs, i) -> {
        Invitation v = new Invitation();
        v.setId(rs.getString("id"));
        v.setInvitationToken(rs.getString("invitation_token"));
        v.setInviteeEmail(rs.getString("invitee_email"));
        v.setWorkspaceId(rs.getString("workspace_id"));
        v.setWorkspaceName(rs.getString("workspace_name"));
        v.setInvitedBy(rs.getString("invited_by"));
        v.setInvitedByEmail(rs.getString("invited_by_email"));
        v.setRole(rs.getString("role"));
        v.setStatus(rs.getString("status"));
        v.setCreatedAt(Instants.readLocal(rs, "created_at"));
        v.setAcceptedAt(Instants.readLocal(rs, "accepted_at"));
        return v;
    };

    private final JdbcTemplate jdbc;

    public JdbcInvitationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Invitation save(Invitation v) {
        v.setId(AuthSql.idOrNew(v.getId()));
        AuthSql.update(jdbc, UPSERT.sql(), v.getId(), v.getInvitationToken(), v.getInviteeEmail(),
                v.getWorkspaceId(), v.getWorkspaceName(), v.getInvitedBy(), v.getInvitedByEmail(), v.getRole(),
                v.getStatus(), AuthSql.db(v.getCreatedAt()), AuthSql.db(v.getAcceptedAt()));
        return v;
    }

    @Override
    public Optional<Invitation> findByInvitationToken(String invitationToken) {
        return invitationToken == null ? Optional.empty()
                : AuthSql.first(jdbc.query("select * from invitation where invitation_token = ?", ROWS,
                invitationToken));
    }

    @Override
    public List<Invitation> findByWorkspaceIdAndStatus(String workspaceId, String status) {
        return jdbc.query("select * from invitation where workspace_id = ? and status = ? order by created_at, id",
                ROWS, workspaceId, status);
    }

    @Override
    public Optional<Invitation> findByInviteeEmailAndWorkspaceId(String inviteeEmail, String workspaceId) {
        return AuthSql.first(jdbc.query("select * from invitation where lower(invitee_email) = lower(?) "
                + "and workspace_id = ? order by created_at desc, id desc limit 1", ROWS, inviteeEmail, workspaceId));
    }

    @Override
    public Optional<Invitation> findByInviteeEmailAndWorkspaceIdAndStatus(String inviteeEmail, String workspaceId,
                                                                          String status) {
        return AuthSql.first(jdbc.query("select * from invitation where lower(invitee_email) = lower(?) "
                + "and workspace_id = ? and status = ? order by created_at desc, id desc limit 1", ROWS,
                inviteeEmail, workspaceId, status));
    }

    @Override
    public boolean existsByInvitationToken(String invitationToken) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from invitation where invitation_token = ?)", Boolean.class,
                invitationToken));
    }

    @Override
    public void deleteByWorkspaceId(String workspaceId) {
        jdbc.update("delete from invitation where workspace_id = ?", workspaceId);
    }
}
