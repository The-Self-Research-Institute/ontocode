package self.research.ontology.plugins.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.plugins.model.PluginRating;

import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcPluginRatingRepository implements PluginRatingRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("plugin_rating")
            .key("id")
            .columns("id", "plugin_id", "user_id", "username", "stars", "review", "merits", "demerits", "recommended",
                    "helpful_count", "created_at", "updated_at")
            .build();

    private static final RowMapper<PluginRating> MAPPER = (rs, i) -> PluginRating.builder()
            .id(rs.getString("id"))
            .pluginId(rs.getString("plugin_id"))
            .userId(rs.getString("user_id"))
            .username(rs.getString("username"))
            .stars(PluginSql.readInt(rs, "stars"))
            .review(rs.getString("review"))
            .merits(rs.getString("merits"))
            .demerits(rs.getString("demerits"))
            .recommended(PluginSql.readBoolean(rs, "recommended"))
            .helpfulCount(PluginSql.readInt(rs, "helpful_count"))
            .createdAt(Instants.readLocal(rs, "created_at"))
            .updatedAt(Instants.readLocal(rs, "updated_at"))
            .build();

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcPluginRatingRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public List<PluginRating> findByPluginId(String pluginId) {
        return jdbc.query("select * from plugin_rating where plugin_id = ? order by created_at, id", MAPPER, pluginId);
    }

    @Override
    public Optional<PluginRating> findByPluginIdAndUserId(String pluginId, String userId) {
        List<PluginRating> rows = jdbc.query("select * from plugin_rating where plugin_id = ? and user_id = ?",
                MAPPER, pluginId, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<PluginRating> findById(String id) {
        List<PluginRating> rows = jdbc.query("select * from plugin_rating where id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public PluginRating save(PluginRating r) {
        if (r.getId() == null) {
            r.setId(Ids.newObjectId());
        }
        PluginSql.update(jdbc, mapper, UPSERT.sql(), r.getId(), r.getPluginId(), r.getUserId(), r.getUsername(),
                r.getStars(), r.getReview(), r.getMerits(), r.getDemerits(), r.getRecommended(), r.getHelpfulCount(),
                Instants.toDb(r.getCreatedAt()), Instants.toDb(r.getUpdatedAt()));
        return r;
    }

    @Override
    public void delete(PluginRating rating) {
        if (rating.getId() != null) {
            jdbc.update("delete from plugin_rating where id = ?", rating.getId());
        }
    }

    @Override
    public void incrementHelpful(String id) {
        jdbc.update("update plugin_rating set helpful_count = coalesce(helpful_count, 0) + 1 where id = ?", id);
    }
}
