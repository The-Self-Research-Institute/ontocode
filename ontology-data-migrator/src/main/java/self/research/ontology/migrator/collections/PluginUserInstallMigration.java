package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.UniqueRule;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class PluginUserInstallMigration implements CollectionMigration {

    @Override
    public String name() {
        return "plugin_user_installs";
    }

    @Override
    public String sourceCollection() {
        return "plugin_user_installs";
    }

    @Override
    public List<UniqueRule> uniqueRules() {
        return List.of(UniqueRule.pair("plugin_id+user_id", "pluginId", "userId"));
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("plugin_user_install", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("plugin_id", Values.required(d, "pluginId"))
                .put("user_id", Values.required(d, "userId"))
                .put("username", Values.text(d, "username"))
                .put("version", Values.text(d, "version"))
                .put("is_active", Values.bool(d, "isActive"))
                .put("total_installs", Values.integer(d, "totalInstalls"))
                .put("first_installed_at", Values.time(d, "firstInstalledAt"))
                .put("last_installed_at", Values.time(d, "lastInstalledAt"))
                .put("last_uninstalled_at", Values.time(d, "lastUninstalledAt"))
                .put("created_at", Values.time(d, "createdAt"))
                .put("updated_at", Values.time(d, "updatedAt"))
                .build());
    }
}
