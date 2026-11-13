package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.document.ProjectShare;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcProjectShareRepository implements ProjectShareRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("project_share")
            .key("id")
            .columns("id", "project_id", "owner_email", "shared_with_emails", "share_link", "permission",
                    "created_at", "updated_at")
            .build();

    private static final RowMapper<ProjectShare> MAPPER = (rs, i) -> {
        ProjectShare share = new ProjectShare();
        share.setId(rs.getString("id"));
        share.setProjectId(rs.getString("project_id"));
        share.setOwnerEmail(rs.getString("owner_email"));
        List<String> emails = EditorSql.readList(rs, "shared_with_emails");
        share.setSharedWithEmails(emails == null ? new ArrayList<>() : emails);
        share.setShareLink(rs.getString("share_link"));
        share.setPermission(rs.getString("permission"));
        share.setCreatedAt(Instants.read(rs, "created_at"));
        share.setUpdatedAt(Instants.read(rs, "updated_at"));
        return share;
    };

    private final JdbcTemplate jdbc;

    public JdbcProjectShareRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProjectShare> findByProjectId(String projectId) {
        return EditorSql.first(jdbc.query("select * from project_share where project_id = ?", MAPPER, projectId));
    }

    @Override
    public List<ProjectShare> findByOwnerEmail(String ownerEmail) {
        return jdbc.query("select * from project_share where owner_email = ? order by created_at, id", MAPPER, ownerEmail);
    }

    @Override
    public List<ProjectShare> findBySharedWithEmailsContaining(String email) {
        return jdbc.query("select * from project_share where ?::text = any (shared_with_emails) order by created_at, id",
                MAPPER, email);
    }

    @Override
    public Optional<ProjectShare> findByShareLink(String shareLink) {
        return EditorSql.first(jdbc.query("select * from project_share where share_link = ?", MAPPER, shareLink));
    }

    @Override
    public boolean existsByProjectId(String projectId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from project_share where project_id = ?)", Boolean.class, projectId));
    }

    @Override
    public ProjectShare save(ProjectShare share) {
        if (share.getId() == null || share.getId().isBlank()) {
            share.setId(Ids.newObjectId());
        }
        EditorSql.update(jdbc, UPSERT.sql(), share.getId(), share.getProjectId(), share.getOwnerEmail(),
                share.getSharedWithEmails() == null ? new ArrayList<String>() : share.getSharedWithEmails(),
                share.getShareLink(), share.getPermission(), Instants.toDb(share.getCreatedAt()),
                Instants.toDb(share.getUpdatedAt()));
        return share;
    }

    @Override
    public void delete(ProjectShare share) {
        jdbc.update("delete from project_share where id = ?", share.getId());
    }

    @Override
    public Optional<ProjectShare> addSharedEmail(String projectId, String email, String permission, Instant now) {
        return EditorSql.first(jdbc.query("update project_share set shared_with_emails = case "
                        + "when ?::text = any (shared_with_emails) then shared_with_emails "
                        + "else array_append(shared_with_emails, ?::text) end, "
                        + "permission = coalesce(nullif(?, ''), permission), updated_at = ? "
                        + "where project_id = ? returning *",
                MAPPER, email, email, permission, Instants.toDb(now), projectId));
    }

    @Override
    public Optional<ProjectShare> removeSharedEmail(String projectId, String email, Instant now) {
        return EditorSql.first(jdbc.query("update project_share set shared_with_emails = "
                        + "array_remove(shared_with_emails, ?::text), updated_at = ? where project_id = ? returning *",
                MAPPER, email, Instants.toDb(now), projectId));
    }
}
