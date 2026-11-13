package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.model.DraftPullRequest;
import self.research.ontology.owlEditor.model.DraftPullRequest.Status;

import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcDraftPullRequestRepository implements DraftPullRequestRepository {

    private static final String NEWEST_FIRST = " order by created_at desc nulls last, id desc";

    private static final SqlUpsert UPSERT = SqlUpsert.into("draft_pull_request")
            .key("id")
            .columns("id", "project_id", "author_id", "author_username", "title", "description", "status",
                    "change_count", "submitted_change_ids", "snapshot_key", "published_change_set_ids",
                    "reverted_at", "reverted_by", "revert_reason", "restored_at", "revert_started_at",
                    "created_at", "reviewed_at", "reviewer_id", "review_note")
            .build();

    private static final RowMapper<DraftPullRequest> MAPPER = (rs, i) -> {
        DraftPullRequest pr = new DraftPullRequest();
        pr.setId(rs.getString("id"));
        pr.setProjectId(rs.getString("project_id"));
        pr.setAuthorId(rs.getString("author_id"));
        pr.setAuthorUsername(rs.getString("author_username"));
        pr.setTitle(rs.getString("title"));
        pr.setDescription(rs.getString("description"));
        String status = rs.getString("status");
        pr.setStatus(status == null ? null : Status.valueOf(status));
        pr.setChangeCount(rs.getInt("change_count"));
        pr.setSubmittedChangeIds(EditorSql.readList(rs, "submitted_change_ids"));
        pr.setSnapshotKey(rs.getString("snapshot_key"));
        pr.setPublishedChangeSetIds(EditorSql.readList(rs, "published_change_set_ids"));
        pr.setRevertedAt(Instants.read(rs, "reverted_at"));
        pr.setRevertedBy(rs.getString("reverted_by"));
        pr.setRevertReason(rs.getString("revert_reason"));
        pr.setRestoredAt(Instants.read(rs, "restored_at"));
        pr.setRevertStartedAt(Instants.read(rs, "revert_started_at"));
        pr.setCreatedAt(Instants.read(rs, "created_at"));
        pr.setReviewedAt(Instants.read(rs, "reviewed_at"));
        pr.setReviewerId(rs.getString("reviewer_id"));
        pr.setReviewNote(rs.getString("review_note"));
        return pr;
    };

    private final JdbcTemplate jdbc;

    public JdbcDraftPullRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DraftPullRequest save(DraftPullRequest pr) {
        if (pr.getId() == null || pr.getId().isBlank()) {
            pr.setId(Ids.newObjectId());
        }
        EditorSql.update(jdbc, UPSERT.sql(), pr.getId(), pr.getProjectId(), pr.getAuthorId(), pr.getAuthorUsername(),
                pr.getTitle(), pr.getDescription(), pr.getStatus() == null ? Status.OPEN.name() : pr.getStatus().name(),
                pr.getChangeCount(), pr.getSubmittedChangeIds(), pr.getSnapshotKey(),
                pr.getPublishedChangeSetIds(), Instants.toDb(pr.getRevertedAt()), pr.getRevertedBy(),
                pr.getRevertReason(), Instants.toDb(pr.getRestoredAt()), Instants.toDb(pr.getRevertStartedAt()),
                Instants.toDb(pr.getCreatedAt()), Instants.toDb(pr.getReviewedAt()), pr.getReviewerId(),
                pr.getReviewNote());
        return pr;
    }

    @Override
    public Optional<DraftPullRequest> findById(String id) {
        return EditorSql.first(jdbc.query("select * from draft_pull_request where id = ?", MAPPER, id));
    }

    @Override
    public List<DraftPullRequest> findAll() {
        return jdbc.query("select * from draft_pull_request order by id", MAPPER);
    }

    @Override
    public void deleteById(String id) {
        jdbc.update("delete from draft_pull_request where id = ?", id);
    }

    @Override
    public List<DraftPullRequest> findByProjectIdOrderByCreatedAtDesc(String projectId) {
        return jdbc.query("select * from draft_pull_request where project_id = ?" + NEWEST_FIRST, MAPPER, projectId);
    }

    @Override
    public List<DraftPullRequest> findByProjectIdAndStatusOrderByCreatedAtDesc(String projectId, Status status) {
        return jdbc.query("select * from draft_pull_request where project_id = ? and status = ?" + NEWEST_FIRST,
                MAPPER, projectId, status.name());
    }

    @Override
    public Optional<DraftPullRequest> findByProjectIdAndAuthorIdAndStatus(String projectId, String authorId, Status status) {
        return EditorSql.first(jdbc.query("select * from draft_pull_request where project_id = ? and author_id = ? "
                + "and status = ?" + NEWEST_FIRST + " limit 1", MAPPER, projectId, authorId, status.name()));
    }

    @Override
    public long countByProjectIdAndStatus(String projectId, Status status) {
        Long count = jdbc.queryForObject(
                "select count(*) from draft_pull_request where project_id = ? and status = ?", Long.class,
                projectId, status.name());
        return count == null ? 0 : count;
    }
}
