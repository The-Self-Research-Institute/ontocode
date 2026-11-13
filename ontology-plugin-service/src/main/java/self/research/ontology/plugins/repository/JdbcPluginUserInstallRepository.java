package self.research.ontology.plugins.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.plugins.model.PluginUserInstall;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcPluginUserInstallRepository implements PluginUserInstallRepository {

    private static final RowMapper<PluginUserInstall> MAPPER = (rs, i) -> PluginUserInstall.builder()
            .id(rs.getString("id"))
            .pluginId(rs.getString("plugin_id"))
            .userId(rs.getString("user_id"))
            .username(rs.getString("username"))
            .version(rs.getString("version"))
            .isActive(PluginSql.readBoolean(rs, "is_active"))
            .totalInstalls(PluginSql.readInt(rs, "total_installs"))
            .firstInstalledAt(Instants.readLocal(rs, "first_installed_at"))
            .lastInstalledAt(Instants.readLocal(rs, "last_installed_at"))
            .lastUninstalledAt(Instants.readLocal(rs, "last_uninstalled_at"))
            .createdAt(Instants.readLocal(rs, "created_at"))
            .updatedAt(Instants.readLocal(rs, "updated_at"))
            .build();

    private final JdbcTemplate jdbc;

    public JdbcPluginUserInstallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PluginUserInstall> findByPluginIdAndUserId(String pluginId, String userId) {
        List<PluginUserInstall> rows = jdbc.query("select * from plugin_user_install where plugin_id = ? and user_id = ?",
                MAPPER, pluginId, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<PluginUserInstall> findByUserId(String userId) {
        return jdbc.query("select * from plugin_user_install where user_id = ? order by created_at, id", MAPPER, userId);
    }

    @Override
    public Long countByPluginId(String pluginId) {
        return jdbc.queryForObject("select count(*) from plugin_user_install where plugin_id = ?", Long.class, pluginId);
    }

    @Override
    public Long countByPluginIdAndIsActive(String pluginId, Boolean isActive) {
        return jdbc.queryForObject("select count(*) from plugin_user_install where plugin_id = ? and is_active = ?",
                Long.class, pluginId, isActive);
    }

    @Override
    public void recordInstall(String pluginId, String userId, String username, String version) {
        var now = Instants.toDb(LocalDateTime.now());
        jdbc.update("insert into plugin_user_install (id, plugin_id, user_id, username, version, is_active, total_installs, "
                        + "first_installed_at, last_installed_at, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, true, 1, ?, ?, ?, ?) "
                        + "on conflict (plugin_id, user_id) do update set version = excluded.version, is_active = true, "
                        + "total_installs = coalesce(plugin_user_install.total_installs, 0) + 1, "
                        + "last_installed_at = excluded.last_installed_at, updated_at = excluded.updated_at",
                Ids.newObjectId(), pluginId, userId, username, version, now, now, now, now);
    }

    @Override
    public boolean recordUninstall(String pluginId, String userId) {
        var now = Instants.toDb(LocalDateTime.now());
        return jdbc.update("update plugin_user_install set is_active = false, last_uninstalled_at = ?, updated_at = ? "
                + "where plugin_id = ? and user_id = ?", now, now, pluginId, userId) > 0;
    }
}
