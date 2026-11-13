package self.research.ontology.auth.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.auth.model.SystemSettings;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;

import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcSystemSettingsRepository implements SystemSettingsRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("system_settings")
            .key("id")
            .columns("id", "maintenance_mode_enabled", "maintenance_message", "maintenance_allowed_domains",
                    "maintenance_allowed_emails", "maintenance_schedule_enabled", "maintenance_all_day_date",
                    "maintenance_start_time", "maintenance_end_time", "maintenance_daily_enabled",
                    "maintenance_daily_start_time", "maintenance_daily_end_time", "maintenance_daily_timezone",
                    "enterprise_domains", "enterprise_emails", "updated_at", "updated_by")
            .build();

    private static final RowMapper<SystemSettings> ROWS = (rs, i) -> {
        SystemSettings s = new SystemSettings();
        s.setId(rs.getString("id"));
        s.setMaintenanceModeEnabled(rs.getBoolean("maintenance_mode_enabled"));
        s.setMaintenanceMessage(rs.getString("maintenance_message"));
        s.setMaintenanceAllowedDomains(AuthSql.readList(rs, "maintenance_allowed_domains"));
        s.setMaintenanceAllowedEmails(AuthSql.readList(rs, "maintenance_allowed_emails"));
        s.setMaintenanceScheduleEnabled(rs.getBoolean("maintenance_schedule_enabled"));
        s.setMaintenanceAllDayDate(rs.getString("maintenance_all_day_date"));
        s.setMaintenanceStartTime(Instants.readLocal(rs, "maintenance_start_time"));
        s.setMaintenanceEndTime(Instants.readLocal(rs, "maintenance_end_time"));
        s.setMaintenanceDailyEnabled(rs.getBoolean("maintenance_daily_enabled"));
        s.setMaintenanceDailyStartTime(rs.getString("maintenance_daily_start_time"));
        s.setMaintenanceDailyEndTime(rs.getString("maintenance_daily_end_time"));
        s.setMaintenanceDailyTimezone(rs.getString("maintenance_daily_timezone"));
        s.setEnterpriseDomains(AuthSql.readList(rs, "enterprise_domains"));
        s.setEnterpriseEmails(AuthSql.readList(rs, "enterprise_emails"));
        s.setUpdatedAt(Instants.readLocal(rs, "updated_at"));
        s.setUpdatedBy(rs.getString("updated_by"));
        return s;
    };

    private final JdbcTemplate jdbc;

    public JdbcSystemSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SystemSettings> findById(String id) {
        return id == null ? Optional.empty()
                : AuthSql.first(jdbc.query("select * from system_settings where id = ?", ROWS, id));
    }

    @Override
    public SystemSettings save(SystemSettings s) {
        s.setId(AuthSql.idOrNew(s.getId()));
        AuthSql.update(jdbc, UPSERT.sql(), s.getId(), s.isMaintenanceModeEnabled(), s.getMaintenanceMessage(),
                AuthSql.textList(s.getMaintenanceAllowedDomains()), AuthSql.textList(s.getMaintenanceAllowedEmails()),
                s.isMaintenanceScheduleEnabled(), s.getMaintenanceAllDayDate(),
                AuthSql.db(s.getMaintenanceStartTime()), AuthSql.db(s.getMaintenanceEndTime()),
                s.isMaintenanceDailyEnabled(), s.getMaintenanceDailyStartTime(), s.getMaintenanceDailyEndTime(),
                s.getMaintenanceDailyTimezone(), AuthSql.textList(s.getEnterpriseDomains()),
                AuthSql.textList(s.getEnterpriseEmails()), AuthSql.db(s.getUpdatedAt()), s.getUpdatedBy());
        return s;
    }
}
