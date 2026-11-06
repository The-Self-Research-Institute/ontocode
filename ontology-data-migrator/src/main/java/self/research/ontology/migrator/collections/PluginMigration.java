package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.UniqueRule;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class PluginMigration implements CollectionMigration {

    @Override
    public String name() {
        return "plugins";
    }

    @Override
    public String sourceCollection() {
        return "plugins";
    }

    @Override
    public List<UniqueRule> uniqueRules() {
        return List.of(UniqueRule.field("plugin_id", "pluginId"));
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("plugin", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("plugin_id", Values.required(d, "pluginId"))
                .put("name", Values.text(d, "name"))
                .put("description", Values.text(d, "description"))
                .put("author", Values.text(d, "author"))
                .put("author_email", Values.text(d, "authorEmail"))
                .put("latest_version", Values.text(d, "latestVersion"))
                .put("category", Values.text(d, "category"))
                .put("keywords", Values.list(d, "keywords"))
                .put("license", Values.text(d, "license"))
                .put("repository", Values.text(d, "repository"))
                .put("homepage", Values.text(d, "homepage"))
                .put("icon", Values.text(d, "icon"))
                .put("screenshots", Values.list(d, "screenshots"))
                .put("total_downloads", Values.longValue(d, "totalDownloads"))
                .put("average_rating", Values.decimal(d, "averageRating"))
                .put("review_count", Values.integer(d, "reviewCount"))
                .put("verified", Values.bool(d, "verified"))
                .put("deprecated", Values.bool(d, "deprecated"))
                .put("created_at", Values.time(d, "createdAt"))
                .put("updated_at", Values.time(d, "updatedAt"))
                .build());
    }
}
