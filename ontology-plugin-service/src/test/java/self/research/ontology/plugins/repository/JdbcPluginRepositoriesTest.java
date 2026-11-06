package self.research.ontology.plugins.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.common.db.testing.EmbeddedTestDatabase;
import self.research.ontology.plugins.model.Plugin;
import self.research.ontology.plugins.model.PluginRating;
import self.research.ontology.plugins.model.PluginUserInstall;
import self.research.ontology.plugins.model.PluginVersion;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcPluginRepositoriesTest {

    private final JdbcTemplate jdbc = EmbeddedTestDatabase.jdbc();
    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcPluginRepository plugins = new JdbcPluginRepository(jdbc, mapper);
    private final JdbcPluginVersionRepository versions = new JdbcPluginVersionRepository(jdbc, mapper);
    private final JdbcPluginRatingRepository ratings = new JdbcPluginRatingRepository(jdbc, mapper);
    private final JdbcPluginUserInstallRepository installs = new JdbcPluginUserInstallRepository(jdbc);

    @BeforeEach
    void clean() {
        jdbc.update("delete from plugin");
        jdbc.update("delete from plugin_version");
        jdbc.update("delete from plugin_rating");
        jdbc.update("delete from plugin_user_install");
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    private Plugin plugin(String pluginId, String name, long downloads) {
        return plugins.save(Plugin.builder().pluginId(pluginId).name(name).description("desc of " + name)
                .author("alice").authorEmail("alice@example.com").latestVersion("1.0.0").category("Editor")
                .keywords(List.of("graph", "view")).license("MIT").screenshots(List.of("a.png", "b.png"))
                .totalDownloads(downloads).averageRating(4.5).reviewCount(2).verified(true).deprecated(false)
                .createdAt(now()).updatedAt(now()).build());
    }

    @Test
    void aPluginRoundTripsEveryFieldIncludingListsAndGetsAnObjectIdShapedId() {
        Plugin saved = plugin("p1", "Graph View", 10);

        Plugin loaded = plugins.findByPluginId("p1").orElseThrow();

        assertEquals(24, saved.getId().length());
        assertEquals(saved, loaded);
        assertEquals(List.of("graph", "view"), loaded.getKeywords());
        assertEquals(List.of("a.png", "b.png"), loaded.getScreenshots());
        assertTrue(plugins.findByPluginId("none").isEmpty());
    }

    @Test
    void emptyAndNullListsAndNullFlagsStayDistinct() {
        Plugin a = plugins.save(Plugin.builder().pluginId("a").name("A").keywords(List.of()).build());
        Plugin b = plugins.save(Plugin.builder().pluginId("b").name("B").build());

        assertEquals(List.of(), plugins.findByPluginId("a").orElseThrow().getKeywords());
        assertNull(plugins.findByPluginId("b").orElseThrow().getKeywords());
        assertNull(plugins.findByPluginId("b").orElseThrow().getDeprecated());
        assertNull(plugins.findByPluginId("b").orElseThrow().getTotalDownloads());
    }

    @Test
    void savingAgainUpdatesAndAPluginIdCannotBeUsedTwice() {
        Plugin saved = plugin("p1", "Old", 1);
        saved.setName("New");
        plugins.save(saved);

        assertEquals("New", plugins.findByPluginId("p1").orElseThrow().getName());
        assertEquals(1, jdbc.queryForObject("select count(*) from plugin", Integer.class));
        assertThrows(DuplicateKeyException.class, () -> plugins.save(Plugin.builder().pluginId("p1").name("Dup").build()));
    }

    @Test
    void deleteRemovesThePlugin() {
        Plugin saved = plugin("p1", "A", 1);

        plugins.delete(saved);

        assertTrue(plugins.findByPluginId("p1").isEmpty());
    }

    @Test
    void activePluginsExcludeOnlyThoseMarkedDeprecatedTrueAndNullCountsAsActive() {
        plugin("ok", "Ok", 1);
        Plugin old = plugin("old", "Old", 2);
        old.setDeprecated(true);
        plugins.save(old);
        plugins.save(Plugin.builder().pluginId("unset").name("Unset").build());

        List<String> ids = plugins.findActivePlugins(PageRequest.of(0, 10)).map(Plugin::getPluginId).toList();

        assertEquals(2, ids.size());
        assertTrue(ids.containsAll(List.of("ok", "unset")));
    }

    @Test
    void browsingPagesAndSortsWithNullsFirstAscendingAndLastDescending() {
        plugin("a", "Alpha", 5);
        plugin("b", "Bravo", 50);
        plugins.save(Plugin.builder().pluginId("n").name("NoDownloads").build());

        Page<Plugin> desc = plugins.findActivePlugins(PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "totalDownloads")));
        Page<Plugin> asc = plugins.findActivePlugins(PageRequest.of(0, 3, Sort.by(Sort.Direction.ASC, "totalDownloads")));
        Page<Plugin> second = plugins.findActivePlugins(PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "totalDownloads")));

        assertEquals(List.of("b", "a"), desc.map(Plugin::getPluginId).toList());
        assertEquals(3, desc.getTotalElements());
        assertEquals(2, desc.getTotalPages());
        assertEquals(List.of("n", "a", "b"), asc.map(Plugin::getPluginId).toList());
        assertEquals(List.of("n"), second.map(Plugin::getPluginId).toList());
        assertThrows(IllegalArgumentException.class,
                () -> plugins.findActivePlugins(PageRequest.of(0, 2, Sort.by("password"))));
    }

    @Test
    void categoryBrowsingFiltersAndCounts() {
        plugin("a", "Alpha", 5);
        Plugin viz = plugin("v", "Viz", 9);
        viz.setCategory("Visualization");
        plugins.save(viz);

        Page<Plugin> page = plugins.findByCategory("Visualization", PageRequest.of(0, 10));

        assertEquals(List.of("v"), page.map(Plugin::getPluginId).toList());
        assertEquals(1, page.getTotalElements());
    }

    @Test
    void searchMatchesNameDescriptionAuthorAndKeywordsIgnoringCaseAsASubstring() {
        plugin("p1", "Graph View", 1);
        Plugin other = plugin("p2", "Theme Pack", 2);
        other.setKeywords(List.of("colour", "dark"));
        other.setAuthor("Zed");
        other.setDescription("makes things pretty");
        plugins.save(other);
        PageRequest page = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "totalDownloads"));

        assertEquals(List.of("p1"), plugins.searchPlugins("GRAPH", page).map(Plugin::getPluginId).toList());
        assertEquals(List.of("p2"), plugins.searchPlugins("pretty", page).map(Plugin::getPluginId).toList());
        assertEquals(List.of("p2"), plugins.searchPlugins("zed", page).map(Plugin::getPluginId).toList());
        assertEquals(List.of("p2"), plugins.searchPlugins("OLO", page).map(Plugin::getPluginId).toList());
        assertEquals(2, plugins.searchPlugins("", page).getTotalElements());
        assertEquals(0, plugins.searchPlugins("nothing-like-this", page).getTotalElements());
    }

    @Test
    void searchTreatsPercentUnderscoreAndBackslashAsPlainText() {
        plugin("p1", "100% Real", 1);
        plugin("p2", "Anything", 2);
        PageRequest page = PageRequest.of(0, 10);

        assertEquals(List.of("p1"), plugins.searchPlugins("100%", page).map(Plugin::getPluginId).toList());
        assertEquals(0, plugins.searchPlugins("%", page).getTotalElements() - 1);
        assertEquals(0, plugins.searchPlugins("_", page).getTotalElements());
        assertEquals(0, plugins.searchPlugins("\\", page).getTotalElements());
        assertEquals(0, plugins.searchPlugins("'; drop table plugin; --", page).getTotalElements());
        assertEquals(2, jdbc.queryForObject("select count(*) from plugin", Integer.class));
    }

    @Test
    void aVersionRoundTripsTheDependencyAndEngineMapsAndOrdersNewestFirst() {
        LocalDateTime t = now();
        versions.save(PluginVersion.builder().pluginId("p1").version("1.0.0").changelog("first").vsixFileId("f1")
                .fileSize(100L).dependencies(Map.of("lib", "^1.2")).engines(Map.of("ontocode", ">=1.0")).entryPoint("index.js")
                .deprecated(false).downloads(0L).publishedAt(t.minusDays(2)).build());
        versions.save(PluginVersion.builder().pluginId("p1").version("1.1.0").vsixFileId("f2").publishedAt(t).build());

        PluginVersion first = versions.findByPluginIdAndVersion("p1", "1.0.0").orElseThrow();

        assertEquals(Map.of("lib", "^1.2"), first.getDependencies());
        assertEquals(Map.of("ontocode", ">=1.0"), first.getEngines());
        assertNull(versions.findByPluginIdAndVersion("p1", "1.1.0").orElseThrow().getDependencies());
        assertEquals(List.of("1.1.0", "1.0.0"),
                versions.findByPluginIdOrderByPublishedAtDesc("p1").stream().map(PluginVersion::getVersion).toList());
        assertThrows(DuplicateKeyException.class,
                () -> versions.save(PluginVersion.builder().pluginId("p1").version("1.0.0").build()));
    }

    @Test
    void deletingAVersionRemovesOnlyThatVersion() {
        PluginVersion one = versions.save(PluginVersion.builder().pluginId("p1").version("1.0.0").build());
        versions.save(PluginVersion.builder().pluginId("p1").version("2.0.0").build());

        versions.delete(one);

        assertEquals(1, versions.findByPluginIdOrderByPublishedAtDesc("p1").size());
    }

    @Test
    void downloadCountersIncrementAtomicallyUnderManyThreadsAndStartFromNull() throws Exception {
        plugins.save(Plugin.builder().pluginId("p1").name("A").build());
        versions.save(PluginVersion.builder().pluginId("p1").version("1.0.0").build());
        int threads = 16;
        int each = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                for (int k = 0; k < each; k++) {
                    versions.incrementDownloads("p1", "1.0.0");
                    plugins.incrementTotalDownloads("p1");
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertEquals(threads * each, versions.findByPluginIdAndVersion("p1", "1.0.0").orElseThrow().getDownloads());
        assertEquals(threads * each, plugins.findByPluginId("p1").orElseThrow().getTotalDownloads());
    }

    private PluginRating rating(String pluginId, String userId, int stars) {
        return ratings.save(PluginRating.builder().pluginId(pluginId).userId(userId).username(userId).stars(stars)
                .helpfulCount(0).recommended(stars >= 4).createdAt(now()).updatedAt(now()).build());
    }

    @Test
    void oneRatingPerUserPerPluginAndFindersAndDelete() {
        PluginRating r = rating("p1", "u1", 5);
        rating("p1", "u2", 3);

        assertEquals(2, ratings.findByPluginId("p1").size());
        assertEquals(5, ratings.findByPluginIdAndUserId("p1", "u1").orElseThrow().getStars());
        assertEquals(r.getId(), ratings.findById(r.getId()).orElseThrow().getId());
        assertThrows(DuplicateKeyException.class, () -> rating("p1", "u1", 1));

        ratings.delete(r);

        assertEquals(1, ratings.findByPluginId("p1").size());
    }

    @Test
    void helpfulCountIncrementsAtomically() throws Exception {
        PluginRating r = rating("p1", "u1", 5);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            futures.add(pool.submit(() -> ratings.incrementHelpful(r.getId())));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertEquals(50, ratings.findById(r.getId()).orElseThrow().getHelpfulCount());
    }

    @Test
    void recomputingTheRatingSetsAverageToOneDecimalAndCountAndResetsToZeroWhenThereAreNoRatings() {
        plugins.save(Plugin.builder().pluginId("p1").name("A").build());
        rating("p1", "u1", 5);
        rating("p1", "u2", 4);
        rating("p1", "u3", 4);

        plugins.recomputeRating("p1");

        Plugin p = plugins.findByPluginId("p1").orElseThrow();
        assertEquals(4.3, p.getAverageRating());
        assertEquals(3, p.getReviewCount());

        ratings.findByPluginId("p1").forEach(ratings::delete);
        plugins.recomputeRating("p1");

        assertEquals(0.0, plugins.findByPluginId("p1").orElseThrow().getAverageRating());
        assertEquals(0, plugins.findByPluginId("p1").orElseThrow().getReviewCount());
    }

    @Test
    void recordingInstallsCountsEachInstallAtomicallyKeepsTheFirstInstallDateAndReinstallsReactivate() throws Exception {
        installs.recordInstall("p1", "u1", "alice", "1.0.0");
        PluginUserInstall first = installs.findByPluginIdAndUserId("p1", "u1").orElseThrow();

        assertEquals(1, first.getTotalInstalls());
        assertTrue(first.getIsActive());
        assertEquals("1.0.0", first.getVersion());

        assertTrue(installs.recordUninstall("p1", "u1"));
        assertFalse(installs.findByPluginIdAndUserId("p1", "u1").orElseThrow().getIsActive());
        assertFalse(installs.recordUninstall("p1", "nobody"));

        installs.recordInstall("p1", "u1", "alice", "1.1.0");
        PluginUserInstall again = installs.findByPluginIdAndUserId("p1", "u1").orElseThrow();
        assertEquals(2, again.getTotalInstalls());
        assertTrue(again.getIsActive());
        assertEquals("1.1.0", again.getVersion());
        assertEquals(first.getFirstInstalledAt(), again.getFirstInstalledAt());
        assertEquals(first.getId(), again.getId());
    }

    @Test
    void manyThreadsInstallingTheSamePluginForTheSameUserNeverFailAndCountEveryInstall() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                for (int k = 0; k < 10; k++) {
                    installs.recordInstall("p1", "u1", "alice", "1.0.0");
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertEquals(1, installs.countByPluginId("p1"));
        assertEquals(threads * 10, installs.findByPluginIdAndUserId("p1", "u1").orElseThrow().getTotalInstalls());
    }

    @Test
    void installCountsActiveCountsAndPerUserListing() {
        installs.recordInstall("p1", "u1", "a", "1");
        installs.recordInstall("p1", "u2", "b", "1");
        installs.recordInstall("p2", "u1", "a", "1");
        installs.recordUninstall("p1", "u2");

        assertEquals(2, installs.countByPluginId("p1"));
        assertEquals(1, installs.countByPluginIdAndIsActive("p1", true));
        assertEquals(1, installs.countByPluginIdAndIsActive("p1", false));
        assertEquals(2, installs.findByUserId("u1").size());
    }

    @Test
    void totalDownloadsCanBeSetFromTheNumberOfInstallRecords() {
        plugins.save(Plugin.builder().pluginId("p1").name("A").totalDownloads(99L).build());
        installs.recordInstall("p1", "u1", "a", "1");
        installs.recordInstall("p1", "u2", "b", "1");

        plugins.setTotalDownloadsFromInstalls("p1");

        assertEquals(2, plugins.findByPluginId("p1").orElseThrow().getTotalDownloads());
    }
}
