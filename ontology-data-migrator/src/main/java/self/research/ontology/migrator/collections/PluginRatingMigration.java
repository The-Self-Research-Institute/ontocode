package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.UniqueRule;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class PluginRatingMigration implements CollectionMigration {

    @Override
    public String name() {
        return "plugin_ratings";
    }

    @Override
    public String sourceCollection() {
        return "plugin_ratings";
    }

    @Override
    public List<UniqueRule> uniqueRules() {
        return List.of(UniqueRule.pair("plugin_id+user_id", "pluginId", "userId"));
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("plugin_rating", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("plugin_id", Values.required(d, "pluginId"))
                .put("user_id", Values.required(d, "userId"))
                .put("username", Values.text(d, "username"))
                .put("stars", Values.integer(d, "stars"))
                .put("review", Values.text(d, "review"))
                .put("merits", Values.text(d, "merits"))
                .put("demerits", Values.text(d, "demerits"))
                .put("recommended", Values.bool(d, "recommended"))
                .put("helpful_count", Values.integer(d, "helpfulCount"))
                .put("created_at", Values.time(d, "createdAt"))
                .put("updated_at", Values.time(d, "updatedAt"))
                .build());
    }
}
