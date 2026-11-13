package self.research.ontology.auth.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlLists;
import self.research.ontology.common.db.sql.SqlUpsert;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository("authWorkspaceRepository")
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcWorkspaceRepository implements WorkspaceRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("workspace")
            .key("id")
            .columns("id", "workspace_id", "owner_id", "name", "description", "subscription_plan", "billing_status",
                    "billing_interval", "subscription_start_date", "subscription_end_date",
                    "subscription_current_period_end", "max_workspaces", "max_members", "collaboration_enabled",
                    "stripe_subscription_id", "pending_checkout_session_id", "pending_checkout_created_at",
                    "created_at", "updated_at", "is_deleted", "deleted_at", "deleted_by")
            .build();

    private static final String NOT_DELETED = "w.is_deleted is not true";

    private static final RowMapper<Workspace> ROWS = (rs, i) -> {
        Workspace w = new Workspace();
        w.setId(rs.getString("id"));
        w.setWorkspaceId(rs.getString("workspace_id"));
        w.setOwnerId(rs.getString("owner_id"));
        w.setName(rs.getString("name"));
        w.setDescription(rs.getString("description"));
        w.setSubscriptionPlan(rs.getString("subscription_plan"));
        w.setBillingStatus(rs.getString("billing_status"));
        w.setBillingInterval(rs.getString("billing_interval"));
        w.setSubscriptionStartDate(Instants.readLocal(rs, "subscription_start_date"));
        w.setSubscriptionEndDate(Instants.readLocal(rs, "subscription_end_date"));
        w.setSubscriptionCurrentPeriodEnd(Instants.readLocal(rs, "subscription_current_period_end"));
        w.setMaxWorkspaces(AuthSql.readInt(rs, "max_workspaces"));
        w.setMaxMembers(AuthSql.readInt(rs, "max_members"));
        w.setCollaborationEnabled(AuthSql.readBoolean(rs, "collaboration_enabled"));
        w.setStripeSubscriptionId(rs.getString("stripe_subscription_id"));
        w.setPendingCheckoutSessionId(rs.getString("pending_checkout_session_id"));
        w.setPendingCheckoutCreatedAt(Instants.readLocal(rs, "pending_checkout_created_at"));
        w.setCreatedAt(Instants.readLocal(rs, "created_at"));
        w.setUpdatedAt(Instants.readLocal(rs, "updated_at"));
        w.setIsDeleted(AuthSql.readBoolean(rs, "is_deleted"));
        w.setDeletedAt(Instants.readLocal(rs, "deleted_at"));
        w.setDeletedBy(rs.getString("deleted_by"));
        w.setMembers(new LinkedHashSet<>());
        return w;
    };

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcWorkspaceRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    private List<Workspace> query(String where, Object... args) {
        List<Workspace> rows = jdbc.query("select w.* from workspace w where " + where + " order by w.id", ROWS, args);
        attachMembers(rows);
        return rows;
    }

    private Optional<Workspace> one(String where, Object... args) {
        List<Workspace> rows = query(where, args);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private void attachMembers(List<Workspace> workspaces) {
        if (workspaces.isEmpty()) {
            return;
        }
        Map<String, Workspace> byRow = new LinkedHashMap<>();
        workspaces.forEach(w -> byRow.put(w.getId(), w));
        SqlLists.inChunks(new ArrayList<>(byRow.keySet()), 1000, ids -> jdbc.query(
                "select * from workspace_member where workspace_row_id in (" + SqlLists.placeholders(ids.size())
                        + ") order by workspace_row_id, position",
                rs -> {
                    Workspace.WorkspaceMember m = new Workspace.WorkspaceMember();
                    m.setUserId(rs.getString("user_id"));
                    m.setUsername(rs.getString("username"));
                    m.setEmail(rs.getString("email"));
                    String role = rs.getString("role");
                    m.setRole(role == null ? null : Workspace.WorkspaceRole.valueOf(role));
                    String status = rs.getString("status");
                    m.setStatus(status == null ? null : Workspace.MemberStatus.valueOf(status));
                    m.setInvitationToken(rs.getString("invitation_token"));
                    m.setJoinedAt(Instants.readLocal(rs, "joined_at"));
                    byRow.get(rs.getString("workspace_row_id")).getMembers().add(m);
                }, ids.toArray()));
    }

    @Override
    public Optional<Workspace> findById(String id) {
        return id == null ? Optional.empty() : one("w.id = ?", id);
    }

    @Override
    public List<Workspace> findAll() {
        return query("true");
    }

    @Override
    public Workspace save(Workspace w) {
        w.setId(AuthSql.idOrNew(w.getId()));
        tx.executeWithoutResult(status -> {
            AuthSql.update(jdbc, UPSERT.sql(), w.getId(), w.getWorkspaceId(), w.getOwnerId(), w.getName(),
                    w.getDescription(), w.getSubscriptionPlan(), w.getBillingStatus(), w.getBillingInterval(),
                    AuthSql.db(w.getSubscriptionStartDate()), AuthSql.db(w.getSubscriptionEndDate()),
                    AuthSql.db(w.getSubscriptionCurrentPeriodEnd()), w.getMaxWorkspaces(), w.getMaxMembers(),
                    w.getCollaborationEnabled(), w.getStripeSubscriptionId(), w.getPendingCheckoutSessionId(),
                    AuthSql.db(w.getPendingCheckoutCreatedAt()), AuthSql.db(w.getCreatedAt()),
                    AuthSql.db(w.getUpdatedAt()), w.getIsDeleted(), AuthSql.db(w.getDeletedAt()), w.getDeletedBy());
            jdbc.update("delete from workspace_member where workspace_row_id = ?", w.getId());
            List<Object[]> batch = new ArrayList<>();
            int position = 0;
            for (Workspace.WorkspaceMember m : w.getMembers()) {
                batch.add(new Object[]{w.getId(), position++, m.getUserId(), m.getUsername(), m.getEmail(),
                        m.getRole() == null ? null : m.getRole().name(),
                        m.getStatus() == null ? null : m.getStatus().name(), m.getInvitationToken(),
                        AuthSql.db(m.getJoinedAt())});
            }
            if (!batch.isEmpty()) {
                jdbc.batchUpdate("insert into workspace_member (workspace_row_id, position, user_id, username, email, "
                        + "role, status, invitation_token, joined_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)", batch);
            }
        });
        return w;
    }

    @Override
    public void delete(Workspace workspace) {
        if (workspace != null && workspace.getId() != null) {
            jdbc.update("delete from workspace where id = ?", workspace.getId());
        }
    }

    @Override
    public void deleteAll(Iterable<? extends Workspace> workspaces) {
        for (Workspace w : workspaces) {
            delete(w);
        }
    }

    @Override
    public List<Workspace> findAllByIsDeletedTrueAndDeletedAtBefore(LocalDateTime cutoff) {
        return query("w.is_deleted is true and w.deleted_at < ?", AuthSql.db(cutoff));
    }

    @Override
    public Optional<Workspace> findByWorkspaceId(String workspaceId) {
        return workspaceId == null ? Optional.empty() : one("w.workspace_id = ?", workspaceId);
    }

    @Override
    public Optional<Workspace> findActiveByWorkspaceId(String workspaceId) {
        return workspaceId == null ? Optional.empty() : one("w.workspace_id = ? and " + NOT_DELETED, workspaceId);
    }

    @Override
    public List<Workspace> findByOwnerId(String ownerId) {
        return query("w.owner_id = ?", ownerId);
    }

    @Override
    public List<Workspace> findActiveByOwnerId(String ownerId) {
        return query("w.owner_id = ? and " + NOT_DELETED, ownerId);
    }

    @Override
    public List<Workspace> findByMembers_UserId(String userId) {
        return query("exists (select 1 from workspace_member m where m.workspace_row_id = w.id and m.user_id = ?)",
                userId);
    }

    @Override
    public List<Workspace> findActiveByMemberEmail(String email) {
        return query("exists (select 1 from workspace_member m where m.workspace_row_id = w.id "
                + "and lower(m.email) = lower(?)) and " + NOT_DELETED, email);
    }

    private static final String OWNER_OR_MEMBER = "(w.owner_id = ? or exists (select 1 from workspace_member m "
            + "where m.workspace_row_id = w.id and m.user_id = ?))";

    @Override
    public List<Workspace> findAllUserWorkspaces(String userId) {
        return query(OWNER_OR_MEMBER, userId, userId);
    }

    @Override
    public List<Workspace> findAllActiveUserWorkspaces(String userId) {
        return query(OWNER_OR_MEMBER + " and " + NOT_DELETED, userId, userId);
    }

    @Override
    public List<Workspace> findDeletedUserWorkspaces(String userId) {
        return query(OWNER_OR_MEMBER + " and w.is_deleted is true", userId, userId);
    }

    @Override
    public boolean existsByWorkspaceId(String workspaceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from workspace where workspace_id = ?)", Boolean.class, workspaceId));
    }

    @Override
    public Optional<Workspace> findByStripeSubscriptionId(String stripeSubscriptionId) {
        return stripeSubscriptionId == null ? Optional.empty()
                : one("w.stripe_subscription_id = ?", stripeSubscriptionId);
    }

    @Override
    public Optional<Workspace> findByPendingCheckoutSessionId(String pendingCheckoutSessionId) {
        return pendingCheckoutSessionId == null ? Optional.empty()
                : one("w.pending_checkout_session_id = ?", pendingCheckoutSessionId);
    }
}
