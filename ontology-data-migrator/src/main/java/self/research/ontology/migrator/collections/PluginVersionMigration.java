package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.UniqueRule;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class PluginVersionMigration implements CollectionMigration {

    @Override
    public String name() {
        return "plugin_versions";
    }

    @Override
    public String sourceCollection() {
        return "plugin_versions";
    }

    @Override
    public List<UniqueRule> uniqueRules() {
        return List.of(UniqueRule.pair("plugin_id+version", "pluginId", "version"));
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("plugin_version", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("plugin_id", Values.required(d, "pluginId"))
                .put("version", Values.required(d, "version"))
                .put("changelog", Values.text(d, "changelog"))
                .put("vsix_file_id", Values.text(d, "vsixFileId"))
                .put("file_size", Values.longValue(d, "fileSize"))
                .put("dependencies", Values.json(d.get("dependencies")))
                .put("engines", Values.json(d.get("engines")))
                .put("entry_point", Values.text(d, "entryPoint"))
                .put("deprecated", Values.bool(d, "deprecated"))
                .put("deprecation_message", Values.text(d, "deprecationMessage"))
                .put("downloads", Values.longValue(d, "downloads"))
                .put("published_at", Values.time(d, "publishedAt"))
                .build());
    }
}
