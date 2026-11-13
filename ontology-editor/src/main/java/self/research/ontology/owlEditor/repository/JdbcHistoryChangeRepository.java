package self.research.ontology.owlEditor.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionOperations;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlLists;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.model.HistoryChange;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcHistoryChangeRepository implements HistoryChangeRepository {

    static final int BATCH_SIZE = 500;

    private static final String[] COLUMN_LIST = {"id", "project_id", "edit_id", "user_id", "username", "changed_at",
            "operation_type", "entity_type", "entity_iri", "entity_label", "old_value", "new_value",
            "annotation_property", "description", "status", "approved_by", "approved_at", "rejected_by", "rejected_at",
            "comments", "has_conflict", "conflict_resolution", "resolved_by", "resolved_at", "metadata", "sub_changes",
            "draft", "draft_discarded", "draft_session_id", "published_pr_id", "reverted", "reverted_audit_id",
            "change_set_id", "exact_deleted", "exact_inserted", "covered_by", "exact_covers", "document_store",
            "undo_blocked_reason", "operation_group_id", "document_id", "document_before", "document_after", "source",
            "ai", "synced_at", "format"};

    private static final String COLUMNS = String.join(", ", COLUMN_LIST);

    private static final SqlUpsert UPSERT = SqlUpsert.into("history_change").key("id").columns(COLUMN_LIST).build();

    private static final TypeReference<Map<String, HistoryChange.CommentEntry>> COMMENTS = new TypeReference<>() {
    };

    private static final TypeReference<List<HistoryChange.SubChange>> SUB_CHANGES = new TypeReference<>() {
    };

    private static final TypeReference<HistoryChange.AiInfo> AI = new TypeReference<>() {
    };

    private static final RowMapper<HistoryChange> MAPPER = (rs, i) -> read(rs);

    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;

    public JdbcHistoryChangeRepository(JdbcTemplate jdbc, TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public HistoryChange save(HistoryChange change) {
        prepare(change);
        EditorSql.update(jdbc, UPSERT.sql(), args(change));
        return change;
    }

    @Override
    public List<HistoryChange> saveAll(List<HistoryChange> changes) {
        if (changes.isEmpty()) {
            return new ArrayList<>();
        }
        List<Object[]> rows = new ArrayList<>(changes.size());
        for (HistoryChange change : changes) {
            prepare(change);
            rows.add(args(change));
        }
        transactions.executeWithoutResult(status ->
                SqlLists.inChunks(rows, BATCH_SIZE, chunk -> EditorSql.batch(jdbc, UPSERT.sql(), chunk)));
        return new ArrayList<>(changes);
    }

    @Override
    public Optional<HistoryChange> findById(String id) {
        return EditorSql.first(jdbc.query("select " + COLUMNS + " from history_change where id = ?", MAPPER, id));
    }

    @Override
    public void deleteByProjectId(String projectId) {
        jdbc.update("delete from history_change where project_id = ?", projectId);
    }

    @Override
    public List<HistoryChange> findByProjectIdOrderByTimestampDesc(String projectId) {
        return jdbc.query(select("project_id = ?") + " order by changed_at desc nulls last, id desc", MAPPER, projectId);
    }

    @Override
    public List<HistoryChange> findByProjectIdAndChangeSetIdOrderByTimestampDesc(String projectId, String changeSetId) {
        return jdbc.query(select("project_id = ? and change_set_id = ?") + " order by changed_at desc nulls last, id desc",
                MAPPER, projectId, changeSetId);
    }

    @Override
    public List<HistoryChange> findByProjectIdAndStatusOrderByTimestampDesc(String projectId, String status) {
        return jdbc.query(select("project_id = ? and status = ?") + " order by changed_at desc nulls last, id desc",
                MAPPER, projectId, status);
    }

    @Override
    public Optional<HistoryChange> findByEditId(String editId) {
        return EditorSql.first(jdbc.query(select("edit_id = ?") + " order by changed_at desc nulls last, id desc limit 1",
                MAPPER, editId));
    }

    @Override
    public List<HistoryChange> findByProjectIdAndImportId(String projectId, String importId) {
        return jdbc.query(select("project_id = ? and metadata ->> 'importId' = ?")
                + " order by changed_at desc nulls last, id desc", MAPPER, projectId, importId);
    }

    @Override
    public long markDraftEntriesShared(String projectId, String entityIri) {
        return jdbc.update("update history_change set draft = false where project_id = ? and entity_iri = ? and draft = true",
                projectId, entityIri);
    }

    @Override
    public boolean existsByProjectIdAndEditId(String projectId, String editId) {
        Boolean exists = jdbc.queryForObject(
                "select exists (select 1 from history_change where project_id = ? and edit_id = ?)", Boolean.class,
                projectId, editId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public List<HistoryChange> findByProjectIdAndDraftAndUserIdInOrderByTimestampDesc(String projectId, boolean draft,
                                                                                        Collection<String> userIds,
                                                                                        Pageable pageable) {
        return EditorSql.query(jdbc, select("project_id = ? and draft = ? and user_id = any(?)")
                + " order by changed_at desc nulls last, id desc" + page(pageable), MAPPER, projectId, draft, userIds);
    }

    @Override
    public List<HistoryChange> findByProjectIdAndEntityIRIOrderByTimestampDesc(String projectId, String entityIRI,
                                                                                 Pageable pageable) {
        return jdbc.query(select("project_id = ? and entity_iri = ?") + " order by changed_at desc nulls last, id desc"
                + page(pageable), MAPPER, projectId, entityIRI);
    }

    @Override
    public List<HistoryChange> findByProjectId(String projectId, Pageable pageable) {
        return jdbc.query(select("project_id = ?") + order(pageable.getSort()) + page(pageable), MAPPER, projectId);
    }

    @Override
    public List<HistoryChange> findByProjectIdAndPublishedPrId(String projectId, String publishedPrId) {
        return jdbc.query(select("project_id = ? and published_pr_id = ?") + " order by changed_at desc nulls last, id desc",
                MAPPER, projectId, publishedPrId);
    }

    @Override
    public List<HistoryChange> findByProjectIdAndDraftOrderByTimestampDesc(String projectId, boolean draft,
                                                                             Pageable pageable) {
        return jdbc.query(select("project_id = ? and draft = ?") + " order by changed_at desc nulls last, id desc"
                + page(pageable), MAPPER, projectId, draft);
    }

    @Override
    public List<HistoryChange> findDraftRowsOfUser(String projectId, String userId) {
        return jdbc.query(select("project_id = ? and user_id = ? and draft = true")
                + " order by changed_at desc nulls last, id desc", MAPPER, projectId, userId);
    }

    @Override
    public long markDraftsPublished(String projectId, String userId) {
        return jdbc.update("update history_change set draft = false where project_id = ? and user_id = ? "
                + "and draft = true and draft_discarded = false", projectId, userId);
    }

    @Override
    public long countUnpublishedRowsOfChangeSets(String projectId, Collection<String> changeSetIds) {
        Long count = EditorSql.query(jdbc, "select count(*) from history_change where project_id = ? and draft = false "
                + "and change_set_id = any(?) and published_pr_id is null", (rs, i) -> rs.getLong(1), projectId,
                changeSetIds).get(0);
        return count == null ? 0 : count;
    }

    @Override
    public long tagRowsOfChangeSets(String projectId, Collection<String> changeSetIds, String prId) {
        return EditorSql.update(jdbc, "update history_change set published_pr_id = ? where project_id = ? "
                + "and draft = false and change_set_id = any(?) and published_pr_id is null", prId, projectId, changeSetIds);
    }

    @Override
    public long countUnlinkedRows(String projectId, String userId, LocalDateTime from, LocalDateTime to,
                                  Collection<String> excludedOperationTypes) {
        return EditorSql.query(jdbc, "select count(*) from history_change where " + UNLINKED, (rs, i) -> rs.getLong(1),
                projectId, userId, excludedOperationTypes, Instants.toDb(from), Instants.toDb(to)).get(0);
    }

    @Override
    public long tagUnlinkedRows(String projectId, String userId, LocalDateTime from, LocalDateTime to,
                                Collection<String> excludedOperationTypes, String prId) {
        return EditorSql.update(jdbc, "update history_change set published_pr_id = ? where " + UNLINKED, prId, projectId,
                userId, excludedOperationTypes, Instants.toDb(from), Instants.toDb(to));
    }

    @Override
    public Optional<HistoryChange> findRecord(String projectId, String entityIri, String operationType) {
        return EditorSql.first(jdbc.query(select("project_id = ? and entity_iri = ? and operation_type = ?")
                + " order by changed_at asc nulls first, id asc limit 1", MAPPER, projectId, entityIri, operationType));
    }

    @Override
    public boolean existsRecord(String projectId, String entityIri, String operationType) {
        Boolean exists = jdbc.queryForObject("select exists (select 1 from history_change where project_id = ? "
                + "and entity_iri = ? and operation_type = ?)", Boolean.class, projectId, entityIri, operationType);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public long markPrRowsReverted(String projectId, String prId) {
        return setRevertedOfPrRows(projectId, prId, false, true);
    }

    @Override
    public long markPrRowsRestored(String projectId, String prId) {
        return setRevertedOfPrRows(projectId, prId, true, false);
    }

    @Override
    public long setRevertedOfRecord(String projectId, String entityIri, String operationType, boolean from, boolean to) {
        return jdbc.update("update history_change set reverted = ? where project_id = ? and entity_iri = ? "
                + "and operation_type = ? and reverted = ?", to, projectId, entityIri, operationType, from);
    }

    @Override
    public long tagRollbackRowsOfChangeSets(String projectId, Collection<String> changeSetIds, String prId) {
        return EditorSql.update(jdbc, "update history_change set published_pr_id = ? where project_id = ? "
                + "and source = 'ROLLBACK' and metadata ->> 'revertsChangeSetId' = any(?) and published_pr_id is null",
                prId, projectId, changeSetIds);
    }

    @Override
    public List<HistoryChange> findRollbackRowsOfPr(String projectId, String prId) {
        return jdbc.query(select("project_id = ? and published_pr_id = ? and source = 'ROLLBACK' "
                + "and starts_with(operation_type, 'ROLLBACK_')") + " order by changed_at desc nulls last, id desc",
                MAPPER, projectId, prId);
    }

    @Override
    public long markChangeSetsPublished(String projectId, Collection<String> changeSetIds) {
        return EditorSql.update(jdbc, "update history_change set draft = false where project_id = ? and draft = true "
                + "and draft_discarded = false and change_set_id = any(?)", projectId, changeSetIds);
    }

    @Override
    public List<String> findLiveDraftChangeSetIds(String projectId, String userId) {
        return jdbc.queryForList("select distinct change_set_id from history_change where project_id = ? and user_id = ? "
                + "and draft = true and draft_discarded = false and reverted = false and change_set_id is not null",
                String.class, projectId, userId);
    }

    @Override
    public long markDraftsDiscarded(String projectId, String userId, Collection<String> keepChangeSetIds) {
        StringBuilder sql = new StringBuilder("update history_change set draft_discarded = true where project_id = ? "
                + "and draft = true and draft_discarded = false");
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        if (userId != null && !userId.isBlank()) {
            sql.append(" and user_id = ?");
            args.add(userId);
        }
        if (keepChangeSetIds != null && !keepChangeSetIds.isEmpty()) {
            sql.append(" and (change_set_id is null or change_set_id <> all(?))");
            args.add(keepChangeSetIds);
        }
        return EditorSql.update(jdbc, sql.toString(), args.toArray());
    }

    @Override
    public void setEntryReverted(String id, boolean reverted, String auditId) {
        jdbc.update("update history_change set reverted = ?, reverted_audit_id = ? where id = ?", reverted, auditId, id);
    }

    @Override
    public void setSubChangesReverted(String id, Collection<String> subChangeIds, boolean reverted, String auditId) {
        if (subChangeIds == null || subChangeIds.isEmpty()) {
            return;
        }
        EditorSql.update(jdbc, "update history_change set sub_changes = (select jsonb_agg(case "
                + "when sc.value ->> 'id' = any(?) then sc.value || jsonb_build_object('reverted', ?::boolean, "
                + "'revertedAuditId', ?::text) else sc.value end order by sc.position) "
                + "from jsonb_array_elements(sub_changes) with ordinality as sc(value, position)) "
                + "where id = ? and jsonb_typeof(sub_changes) = 'array' and jsonb_array_length(sub_changes) > 0",
                subChangeIds, reverted, auditId, id);
    }

    @Override
    public int backfillSubChangeIds() {
        Long updated = jdbc.queryForObject("with target as (select h.id, (select count(*) from "
                + "jsonb_array_elements(h.sub_changes) e where jsonb_typeof(e -> 'id') is distinct from 'string') as missing "
                + "from history_change h where jsonb_typeof(h.sub_changes) = 'array'), "
                + "changed as (update history_change h set sub_changes = (select jsonb_agg(case "
                + "when jsonb_typeof(sc.value -> 'id') = 'string' then sc.value "
                + "else sc.value || jsonb_build_object('id', gen_random_uuid()::text) end order by sc.position) "
                + "from jsonb_array_elements(h.sub_changes) with ordinality as sc(value, position)) "
                + "from target t where h.id = t.id and t.missing > 0 returning t.missing) "
                + "select coalesce(sum(missing), 0) from changed", Long.class);
        return updated == null ? 0 : updated.intValue();
    }

    @Override
    public List<DraftGroup> countLiveDraftGroups() {
        return jdbc.query("select project_id, user_id, count(*) as rows from history_change "
                + "where draft = true and draft_discarded = false group by project_id, user_id",
                (rs, i) -> new DraftGroup(rs.getString("project_id"), rs.getString("user_id"), rs.getLong("rows")));
    }

    @Override
    public List<SameNameGroup> findNamesUnderSeveralUserIds() {
        return jdbc.query("select project_id, username, array_agg(distinct user_id) filter (where user_id is not null) as ids "
                + "from history_change where username is not null group by project_id, username "
                + "having count(distinct user_id) > 1", (rs, i) -> new SameNameGroup(rs.getString("project_id"),
                rs.getString("username"), EditorSql.readList(rs, "ids")));
    }

    @Override
    public long tagOperationGroup(String projectId, String userId, LocalDateTime since, String groupId) {
        return jdbc.update("update history_change set operation_group_id = ? where project_id = ? and user_id = ? "
                + "and draft = false and changed_at >= ? and operation_group_id is null", groupId, projectId, userId,
                Instants.toDb(since));
    }

    @Override
    public List<HistoryChange> findOpenByOperationGroupId(String operationGroupId) {
        return jdbc.query(select("operation_group_id = ? and reverted = false")
                + " order by changed_at desc nulls last, id desc", MAPPER, operationGroupId);
    }

    @Override
    public void blockExact(String id, String editId, String projectId, String reason) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("update history_change set undo_blocked_reason = ?, exact_deleted = null, exact_inserted = null, "
                    + "exact_covers = 0 where id = ?", reason, id);
            jdbc.update("update history_change set undo_blocked_reason = ?, covered_by = null "
                    + "where covered_by = ? and project_id = ?", reason, editId, projectId);
        });
    }

    @Override
    public void setExactCovers(String projectId, String editId, int covers) {
        jdbc.update("update history_change set exact_covers = ? where id = (select id from history_change "
                + "where project_id = ? and edit_id = ? order by changed_at asc nulls first, id asc limit 1)",
                covers, projectId, editId);
    }

    private long setRevertedOfPrRows(String projectId, String prId, boolean from, boolean to) {
        return jdbc.update("update history_change set reverted = ? where project_id = ? and published_pr_id = ? "
                + "and source is distinct from 'ROLLBACK' and reverted = ?", to, projectId, prId, from);
    }

    private static final String UNLINKED = "project_id = ? and user_id = ? and draft = false and published_pr_id is null "
            + "and source is distinct from 'ROLLBACK' and coalesce(operation_type, '') <> all(?) "
            + "and changed_at >= ? and changed_at <= ? "
            + "and (change_set_id is null or operation_type = 'publishDraft')";

    private void prepare(HistoryChange change) {
        if (change.getId() == null || change.getId().isBlank()) {
            change.setId(Ids.newObjectId());
        }
        if (change.isDraft() && change.getDraftSessionId() == null
                && change.getProjectId() != null && change.getUserId() != null) {
            try {
                List<String> sessions = jdbc.queryForList("select id from draft_session where project_id = ? "
                        + "and user_id = ? limit 1", String.class, change.getProjectId(), change.getUserId());
                if (!sessions.isEmpty()) {
                    change.setDraftSessionId(sessions.get(0));
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static String select(String where) {
        return "select " + COLUMNS + " from history_change where " + where;
    }

    private static String page(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return "";
        }
        return " limit " + pageable.getPageSize() + " offset " + pageable.getOffset();
    }

    private static String order(Sort sort) {
        List<String> parts = new ArrayList<>();
        if (sort != null) {
            for (Sort.Order order : sort) {
                String column = switch (order.getProperty()) {
                    case "timestamp" -> "changed_at";
                    case "_id", "id" -> "id";
                    default -> null;
                };
                if (column != null) {
                    parts.add(column + (order.isAscending() ? " asc nulls first" : " desc nulls last"));
                }
            }
        }
        return parts.isEmpty() ? " order by changed_at desc nulls last, id desc" : " order by " + String.join(", ", parts);
    }

    private static Object[] args(HistoryChange c) {
        return new Object[] {c.getId(), c.getProjectId(), c.getEditId(), c.getUserId(), c.getUsername(),
                Instants.toDb(c.getTimestamp()), c.getOperationType(), c.getEntityType(), c.getEntityIRI(),
                c.getEntityLabel(), c.getOldValue(), c.getNewValue(), c.getAnnotationProperty(), c.getDescription(),
                c.getStatus() == null ? "SAVED" : c.getStatus(), c.getApprovedBy(), Instants.toDb(c.getApprovedAt()),
                c.getRejectedBy(), Instants.toDb(c.getRejectedAt()), EditorSql.json(c.getComments()),
                c.isHasConflict(), c.getConflictResolution(), c.getResolvedBy(), Instants.toDb(c.getResolvedAt()),
                EditorSql.json(c.getMetadata()), EditorSql.json(c.getSubChanges()), c.isDraft(), c.isDraftDiscarded(),
                c.getDraftSessionId(), c.getPublishedPrId(), c.isReverted(), c.getRevertedAuditId(), c.getChangeSetId(),
                c.getExactDeleted(), c.getExactInserted(), c.getCoveredBy(), c.getExactCovers(), c.getDocumentStore(),
                c.storedUndoBlockedReason(), c.getOperationGroupId(), c.getDocumentId(), c.getDocumentBefore(),
                c.getDocumentAfter(), c.getSource(), EditorSql.json(c.getAi()), Instants.toDb(c.getSyncedAt()),
                c.getFormat()};
    }

    private static HistoryChange read(ResultSet rs) throws SQLException {
        HistoryChange c = new HistoryChange();
        c.setId(rs.getString("id"));
        c.setProjectId(rs.getString("project_id"));
        c.setEditId(rs.getString("edit_id"));
        c.setUserId(rs.getString("user_id"));
        c.setUsername(rs.getString("username"));
        c.setTimestamp(Instants.readLocal(rs, "changed_at"));
        c.setOperationType(rs.getString("operation_type"));
        c.setEntityType(rs.getString("entity_type"));
        c.setEntityIRI(rs.getString("entity_iri"));
        c.setEntityLabel(rs.getString("entity_label"));
        c.setOldValue(rs.getString("old_value"));
        c.setNewValue(rs.getString("new_value"));
        c.setAnnotationProperty(rs.getString("annotation_property"));
        c.setDescription(rs.getString("description"));
        c.setStatus(rs.getString("status"));
        c.setApprovedBy(rs.getString("approved_by"));
        c.setApprovedAt(Instants.readLocal(rs, "approved_at"));
        c.setRejectedBy(rs.getString("rejected_by"));
        c.setRejectedAt(Instants.readLocal(rs, "rejected_at"));
        Map<String, HistoryChange.CommentEntry> comments = EditorSql.readJson(rs, "comments", COMMENTS);
        if (comments != null) {
            c.setComments(new java.util.HashMap<>(comments));
        }
        c.setHasConflict(rs.getBoolean("has_conflict"));
        c.setConflictResolution(rs.getString("conflict_resolution"));
        c.setResolvedBy(rs.getString("resolved_by"));
        c.setResolvedAt(Instants.readLocal(rs, "resolved_at"));
        Map<String, String> metadata = EditorSql.readStringMap(rs, "metadata");
        if (metadata != null) {
            c.setMetadata(new java.util.HashMap<>(metadata));
        }
        List<HistoryChange.SubChange> subChanges = EditorSql.readJson(rs, "sub_changes", SUB_CHANGES);
        c.setSubChanges(subChanges == null ? null : new ArrayList<>(subChanges));
        c.setDraft(rs.getBoolean("draft"));
        c.setDraftDiscarded(rs.getBoolean("draft_discarded"));
        c.setDraftSessionId(rs.getString("draft_session_id"));
        c.setPublishedPrId(rs.getString("published_pr_id"));
        c.setReverted(rs.getBoolean("reverted"));
        c.setRevertedAuditId(rs.getString("reverted_audit_id"));
        c.setChangeSetId(rs.getString("change_set_id"));
        c.setExactDeleted(rs.getString("exact_deleted"));
        c.setExactInserted(rs.getString("exact_inserted"));
        c.setCoveredBy(rs.getString("covered_by"));
        c.setExactCovers(rs.getInt("exact_covers"));
        c.setDocumentStore(rs.getString("document_store"));
        c.setUndoBlockedReason(rs.getString("undo_blocked_reason"));
        c.setOperationGroupId(rs.getString("operation_group_id"));
        c.setDocumentId(rs.getString("document_id"));
        c.setDocumentBefore(rs.getString("document_before"));
        c.setDocumentAfter(rs.getString("document_after"));
        c.setSource(rs.getString("source"));
        c.setAi(EditorSql.readJson(rs, "ai", AI));
        c.setSyncedAt(Instants.readLocal(rs, "synced_at"));
        c.setFormat(rs.getString("format"));
        return c;
    }
}
