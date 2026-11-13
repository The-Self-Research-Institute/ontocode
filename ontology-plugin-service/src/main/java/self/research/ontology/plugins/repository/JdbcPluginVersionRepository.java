package self.research.ontology.plugins.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.plugins.model.PluginVersion;

import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcPluginVersionRepository implements PluginVersionRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("plugin_version")
            .key("id")
            .columns("id", "plugin_id", "version", "changelog", "vsix_file_id", "file_size", "dependencies", "engines",
                    "entry_point", "deprecated", "deprecation_message", "downloads", "published_at")
            .build();

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RowMapper<PluginVersion> rowMapper;

    public JdbcPluginVersionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.rowMapper = (rs, i) -> PluginVersion.builder()
                .id(rs.getString("id"))
                .pluginId(rs.getString("plugin_id"))
                .version(rs.getString("version"))
                .changelog(rs.getString("changelog"))
                .vsixFileId(rs.getString("vsix_file_id"))
                .fileSize(PluginSql.readLong(rs, "file_size"))
                .dependencies(PluginSql.readMap(mapper, rs, "dependencies"))
                .engines(PluginSql.readMap(mapper, rs, "engines"))
                .entryPoint(rs.getString("entry_point"))
                .deprecated(PluginSql.readBoolean(rs, "deprecated"))
                .deprecationMessage(rs.getString("deprecation_message"))
                .downloads(PluginSql.readLong(rs, "downloads"))
                .publishedAt(Instants.readLocal(rs, "published_at"))
                .build();
    }

    @Override
    public List<PluginVersion> findByPluginIdOrderByPublishedAtDesc(String pluginId) {
        return jdbc.query("select * from plugin_version where plugin_id = ? order by published_at desc nulls last, id desc",
                rowMapper, pluginId);
    }

    @Override
    public Optional<PluginVersion> findByPluginIdAndVersion(String pluginId, String version) {
        List<PluginVersion> rows = jdbc.query("select * from plugin_version where plugin_id = ? and version = ?",
                rowMapper, pluginId, version);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public PluginVersion save(PluginVersion v) {
        if (v.getId() == null) {
            v.setId(Ids.newObjectId());
        }
        PluginSql.update(jdbc, mapper, UPSERT.sql(), v.getId(), v.getPluginId(), v.getVersion(), v.getChangelog(),
                v.getVsixFileId(), v.getFileSize(), v.getDependencies(), v.getEngines(), v.getEntryPoint(),
                v.getDeprecated(), v.getDeprecationMessage(), v.getDownloads(), Instants.toDb(v.getPublishedAt()));
        return v;
    }

    @Override
    public void delete(PluginVersion version) {
        if (version.getId() != null) {
            jdbc.update("delete from plugin_version where id = ?", version.getId());
        }
    }

    @Override
    public void incrementDownloads(String pluginId, String version) {
        jdbc.update("update plugin_version set downloads = coalesce(downloads, 0) + 1 where plugin_id = ? and version = ?",
                pluginId, version);
    }
}
