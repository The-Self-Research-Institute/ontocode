package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class SystemSettingsMigration implements CollectionMigration {

    @Override
    public String name() {
        return "system_settings";
    }

    @Override
    public String sourceCollection() {
        return "system_settings";
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("system_settings", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("maintenance_mode_enabled", Values.bool(d, "maintenanceModeEnabled", false))
                .put("maintenance_message", orDefault(Values.text(d, "maintenanceMessage"), ""))
                .put("maintenance_allowed_domains", Values.listOrEmpty(d, "maintenanceAllowedDomains"))
                .put("maintenance_allowed_emails", Values.listOrEmpty(d, "maintenanceAllowedEmails"))
                .put("maintenance_schedule_enabled", Values.bool(d, "maintenanceScheduleEnabled", false))
                .put("maintenance_all_day_date", Values.text(d, "maintenanceAllDayDate"))
                .put("maintenance_start_time", Values.time(d, "maintenanceStartTime"))
                .put("maintenance_end_time", Values.time(d, "maintenanceEndTime"))
                .put("maintenance_daily_enabled", Values.bool(d, "maintenanceDailyEnabled", false))
                .put("maintenance_daily_start_time", orDefault(Values.text(d, "maintenanceDailyStartTime"), "09:00"))
                .put("maintenance_daily_end_time", orDefault(Values.text(d, "maintenanceDailyEndTime"), "19:00"))
                .put("maintenance_daily_timezone", orDefault(Values.text(d, "maintenanceDailyTimezone"), "Asia/Kolkata"))
                .put("enterprise_domains", Values.listOrEmpty(d, "enterpriseDomains"))
                .put("enterprise_emails", Values.listOrEmpty(d, "enterpriseEmails"))
                .put("updated_at", Values.time(d, "updatedAt"))
                .put("updated_by", Values.text(d, "updatedBy"))
                .build());
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
