package self.research.ontology.plugins.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.plugins.model.Plugin;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcPluginRepository implements PluginRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "name",
            "createdAt", "created_at",
            "averageRating", "average_rating",
            "totalDownloads", "total_downloads",
            "updatedAt", "updated_at");

    private static final SqlUpsert UPSERT = SqlUpsert.into("plugin")
            .key("id")
            .columns("id", "plugin_id", "name", "description", "author", "author_email", "latest_version", "category",
                    "keywords", "license", "repository", "homepage", "icon", "screenshots", "total_downloads",
                    "average_rating", "review_count", "verified", "deprecated", "created_at", "updated_at")
            .build();

    private static final RowMapper<Plugin> MAPPER = (rs, i) -> Plugin.builder()
            .id(rs.getString("id"))
            .pluginId(rs.getString("plugin_id"))
            .name(rs.getString("name"))
            .description(rs.getString("description"))
            .author(rs.getString("author"))
            .authorEmail(rs.getString("author_email"))
            .latestVersion(rs.getString("latest_version"))
            .category(rs.getString("category"))
            .keywords(PluginSql.readList(rs, "keywords"))
            .license(rs.getString("license"))
            .repository(rs.getString("repository"))
            .homepage(rs.getString("homepage"))
            .icon(rs.getString("icon"))
            .screenshots(PluginSql.readList(rs, "screenshots"))
            .totalDownloads(PluginSql.readLong(rs, "total_downloads"))
            .averageRating(PluginSql.readDouble(rs, "average_rating"))
            .reviewCount(PluginSql.readInt(rs, "review_count"))
            .verified(PluginSql.readBoolean(rs, "verified"))
            .deprecated(PluginSql.readBoolean(rs, "deprecated"))
            .createdAt(Instants.readLocal(rs, "created_at"))
            .updatedAt(Instants.readLocal(rs, "updated_at"))
            .build();

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcPluginRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<Plugin> findByPluginId(String pluginId) {
        List<Plugin> rows = jdbc.query("select * from plugin where plugin_id = ?", MAPPER, pluginId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Page<Plugin> findByCategory(String category, Pageable pageable) {
        return pageOf("category = ?", List.of(category), pageable);
    }

    @Override
    public Page<Plugin> searchPlugins(String searchTerm, Pageable pageable) {
        String like = PluginSql.likePattern(searchTerm);
        String where = "(name ilike ? escape '\\' or description ilike ? escape '\\' or author ilike ? escape '\\' "
                + "or exists (select 1 from unnest(keywords) k where k ilike ? escape '\\'))";
        return pageOf(where, List.of(like, like, like, like), pageable);
    }

    @Override
    public Page<Plugin> findActivePlugins(Pageable pageable) {
        return pageOf("deprecated is not true", List.of(), pageable);
    }

    @Override
    public Plugin save(Plugin p) {
        if (p.getId() == null) {
            p.setId(Ids.newObjectId());
        }
        PluginSql.update(jdbc, mapper, UPSERT.sql(), p.getId(), p.getPluginId(), p.getName(), p.getDescription(),
                p.getAuthor(), p.getAuthorEmail(), p.getLatestVersion(), p.getCategory(), p.getKeywords(), p.getLicense(),
                p.getRepository(), p.getHomepage(), p.getIcon(), p.getScreenshots(), p.getTotalDownloads(),
                p.getAverageRating(), p.getReviewCount(), p.getVerified(), p.getDeprecated(),
                Instants.toDb(p.getCreatedAt()), Instants.toDb(p.getUpdatedAt()));
        return p;
    }

    @Override
    public void delete(Plugin plugin) {
        if (plugin.getId() != null) {
            jdbc.update("delete from plugin where id = ?", plugin.getId());
        }
    }

    @Override
    public void incrementTotalDownloads(String pluginId) {
        jdbc.update("update plugin set total_downloads = coalesce(total_downloads, 0) + 1 where plugin_id = ?", pluginId);
    }

    @Override
    public void setTotalDownloadsFromInstalls(String pluginId) {
        jdbc.update("update plugin set total_downloads = (select count(*) from plugin_user_install where plugin_id = ?), "
                + "updated_at = ? where plugin_id = ?", pluginId, Instants.toDb(LocalDateTime.now()), pluginId);
    }

    @Override
    public void recomputeRating(String pluginId) {
        jdbc.update("update plugin set "
                        + "average_rating = coalesce((select round(avg(stars)::numeric, 1)::double precision from plugin_rating where plugin_id = ?), 0.0), "
                        + "review_count = (select count(*) from plugin_rating where plugin_id = ?), "
                        + "updated_at = ? where plugin_id = ?",
                pluginId, pluginId, Instants.toDb(LocalDateTime.now()), pluginId);
    }

    private Page<Plugin> pageOf(String where, List<Object> args, Pageable pageable) {
        long total = jdbc.queryForObject("select count(*) from plugin where " + where, Long.class, args.toArray());
        String order = PluginSql.orderBy(pageable.getSort(), SORT_COLUMNS, "id asc");
        List<Object> all = new ArrayList<>(args);
        all.add(pageable.getPageSize());
        all.add(pageable.getOffset());
        List<Plugin> rows = jdbc.query("select * from plugin where " + where + " order by " + order + " limit ? offset ?",
                MAPPER, all.toArray());
        return PluginSql.page(rows, pageable, total);
    }
}
