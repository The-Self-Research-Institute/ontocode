package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.SqlLists;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcProjectAccessRepository implements ProjectAccessRepository {

    private static final String PROJECT_ACCESS = "select 1 from project p where p.project_id = ? and p.is_deleted is not true "
            + "and (p.owner_id = ?::text or exists (select 1 from project_member m where m.project_row_id = p.id "
            + "and (m.user_id = ?::text or lower(m.email) = lower(?::text))))";

    private static final String FILE_OWNED = "select 1 from ontology_file f where (f.id = ? or f.project_id = ?) "
            + "and (f.owner_id = ?::text or lower(f.owner_email) = lower(?::text))";

    private final JdbcTemplate jdbc;

    public JdbcProjectAccessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean projectExists(String projectId, String parentProjectId) {
        return query("select exists (select 1 from ontology_file where id = ?) "
                        + "or exists (select 1 from project where project_id = ? and is_deleted is not true) "
                        + "or exists (select 1 from ontology_file where project_id = ?)",
                projectId, parentProjectId, parentProjectId);
    }

    @Override
    public boolean hasAccess(String projectId, String parentProjectId, String userId, String email) {
        return query("select exists (" + PROJECT_ACCESS + ") or exists (" + FILE_OWNED + ")",
                parentProjectId, blankToNull(userId), blankToNull(userId), blankToNull(email),
                projectId, parentProjectId, blankToNull(userId), blankToNull(email));
    }

    @Override
    public AccessibleProjects accessibleProjects(String userId, String email) {
        String id = blankToNull(userId);
        String mail = blankToNull(email);
        Set<String> projectIds = new HashSet<>();
        Set<String> parentIds = new HashSet<>();
        jdbc.query("select 'p' as kind, p.project_id as value from project p where p.is_deleted is not true "
                        + "and (p.owner_id = ?::text or exists (select 1 from project_member m where m.project_row_id = p.id "
                        + "and (m.user_id = ?::text or lower(m.email) = lower(?::text)))) "
                        + "union all select 'f', f.id from ontology_file f "
                        + "where f.owner_id = ?::text or lower(f.owner_email) = lower(?::text) "
                        + "union all select 'q', f.project_id from ontology_file f where f.project_id is not null "
                        + "and (f.owner_id = ?::text or lower(f.owner_email) = lower(?::text))",
                rs -> {
                    String kind = rs.getString("kind");
                    String value = rs.getString("value");
                    if (value == null) {
                        return;
                    }
                    if ("f".equals(kind)) {
                        projectIds.add(value);
                    } else {
                        parentIds.add(value);
                    }
                }, id, id, mail, id, mail, id, mail);
        return new AccessibleProjects(projectIds, parentIds);
    }

    @Override
    public Optional<WorkspaceProject> findWorkspaceProject(String parentProjectId) {
        return EditorSql.first(jdbc.query("select p.id, p.project_id, p.workspace_id, p.owner_id, "
                        + "coalesce(nullif((select u.email from app_user u where u.id = p.owner_id), ''), "
                        + "(select m.email from project_member m where m.project_row_id = p.id "
                        + "and upper(m.role) = 'OWNER' and coalesce(m.email, '') <> '' order by m.position limit 1)) "
                        + "as owner_email, (select count(*) from project_member m where m.project_row_id = p.id) as member_count "
                        + "from project p where p.project_id = ? and p.is_deleted is not true "
                        + "and exists (select 1 from project_member m where m.project_row_id = p.id) limit 1",
                (rs, i) -> new WorkspaceProject(rs.getString("id"), rs.getString("project_id"),
                        rs.getString("workspace_id"), rs.getString("owner_id"), rs.getString("owner_email"),
                        rs.getInt("member_count")), parentProjectId));
    }

    @Override
    public boolean ownsAnyRecord(String projectId, String parentProjectId, String userId, String email) {
        return query("select exists (select 1 from project p where p.project_id = ? and p.is_deleted is not true "
                        + "and p.owner_id = ?::text) or exists (" + FILE_OWNED + ")",
                parentProjectId, blankToNull(userId), projectId, parentProjectId, blankToNull(userId), blankToNull(email));
    }

    @Override
    public boolean hasMemberRole(String parentProjectId, String userId, Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        List<Object> args = new ArrayList<>();
        args.add(parentProjectId);
        args.add(userId);
        roles.forEach(role -> args.add(role.toUpperCase(Locale.ROOT)));
        return query("select exists (select 1 from project p join project_member m on m.project_row_id = p.id "
                + "where p.project_id = ? and p.is_deleted is not true and m.user_id = ? "
                + "and upper(m.role) in (" + SqlLists.placeholders(roles.size()) + "))", args.toArray());
    }

    @Override
    public boolean isWorkspaceAdmin(String workspaceId, String userId) {
        return query("select exists (select 1 from workspace w join workspace_member m on m.workspace_row_id = w.id "
                + "where w.workspace_id = ? and m.user_id = ? and upper(m.role) = 'ADMIN')", workspaceId, userId);
    }

    @Override
    public Optional<String> findWorkspaceIdOfProject(String projectId, String parentProjectId) {
        List<String> rows = jdbc.queryForList("select coalesce("
                        + "(select nullif(trim(f.workspace_id), '') from ontology_file f where f.id = ?), "
                        + "(select nullif(trim(p.workspace_id), '') from project p where p.project_id = ?), "
                        + "(select nullif(trim(f.workspace_id), '') from ontology_file f where f.id = ?))",
                String.class, projectId, parentProjectId, parentProjectId);
        return rows.isEmpty() || rows.get(0) == null ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<String> findUserIdByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return EditorSql.first(jdbc.queryForList("select id from app_user where lower(email) = lower(?) limit 1",
                String.class, email.trim()));
    }

    private boolean query(String sql, Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
