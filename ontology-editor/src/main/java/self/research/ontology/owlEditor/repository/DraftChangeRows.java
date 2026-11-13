package self.research.ontology.owlEditor.repository;

import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.owlEditor.model.DraftChange;

import java.sql.ResultSet;
import java.sql.SQLException;

final class DraftChangeRows {

    private DraftChangeRows() {
    }

    static DraftChange read(ResultSet rs, String id, String userId, String username, String type, String data,
                            String at, String sessionId, String applied) throws SQLException {
        DraftChange change = new DraftChange();
        change.setId(rs.getString(id));
        change.setUserId(rs.getString(userId));
        change.setUsername(rs.getString(username));
        change.setOperationType(rs.getString(type));
        change.setOperationData(EditorSql.readMap(rs, data));
        change.setTimestamp(Instants.readLocal(rs, at));
        change.setSessionId(rs.getString(sessionId));
        change.setApplied(rs.getBoolean(applied));
        return change;
    }

    static DraftChange read(ResultSet rs) throws SQLException {
        DraftChange change = read(rs, "id", "user_id", "username", "operation_type", "operation_data", "changed_at",
                "session_id", "applied");
        change.setProjectId(rs.getString("project_id"));
        return change;
    }
}
