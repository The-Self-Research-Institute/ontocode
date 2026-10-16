package self.research.ontology.owlEditor.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.service.Limit;
import self.research.ontology.owlEditor.service.Limit.Profile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapacityPropertiesTest {

    private static final long GB = 1024L * 1024L * 1024L;

    @AfterEach
    void restoreDefaults() {
        Limit.install(Limit.resolve(Profile.STANDARD, Map.of()));
    }

    private static int at(int[] values, Limit limit) {
        return values[limit.ordinal()];
    }

    @Test
    void profileFollowsTheHeapSize() {
        assertEquals(Profile.SMALL, Limit.profileFor(GB / 2));
        assertEquals(Profile.STANDARD, Limit.profileFor(2 * GB));
        assertEquals(Profile.LARGE, Limit.profileFor(8 * GB));
    }

    @Test
    void autoUsesThePresetForTheHeap() {
        int[] values = new CapacityProperties().resolve(8 * GB);
        for (Limit limit : Limit.values()) {
            assertEquals(limit.preset(Profile.LARGE), at(values, limit), limit.key());
        }
    }

    @Test
    void anExplicitProfileBeatsTheHeap() {
        CapacityProperties props = new CapacityProperties();
        props.setProfile("small");
        assertEquals(Limit.WHOLE_GRAPH_MAX_TRIPLES.preset(Profile.SMALL), at(props.resolve(32 * GB), Limit.WHOLE_GRAPH_MAX_TRIPLES));
    }

    @Test
    void overridesBeatThePresetAcceptAnyKeySpellingAndAreFloored() {
        CapacityProperties props = new CapacityProperties();
        props.setLimits(Map.of("whole-graph-max-triples", 90_000, "FEEDPAGESIZE", 1, "no-such-setting", 5));
        int[] values = props.resolve(2 * GB);
        assertEquals(90_000, at(values, Limit.WHOLE_GRAPH_MAX_TRIPLES));
        assertEquals(Limit.FEED_PAGE_SIZE.floor(), at(values, Limit.FEED_PAGE_SIZE));
        assertEquals(Limit.MAX_DELTA_TRIPLES.preset(Profile.STANDARD), at(values, Limit.MAX_DELTA_TRIPLES));
    }

    @Test
    void anUnknownProfileFallsBackToAuto() {
        CapacityProperties props = new CapacityProperties();
        props.setProfile("huge");
        assertEquals(Limit.CANONICAL_ROUNDS.preset(Profile.LARGE), at(props.resolve(8 * GB), Limit.CANONICAL_ROUNDS));
    }

    @Test
    void installedValuesAreWhatTheLimitsReport() {
        CapacityProperties props = new CapacityProperties();
        props.setLimits(Map.of("max-delta-triples", 777));
        Limit.install(props.resolve(2 * GB));
        assertEquals(777, Limit.MAX_DELTA_TRIPLES.value());
    }

    @Test
    void everyPresetIsOrderedAndAboveItsFloor() {
        for (Limit limit : Limit.values()) {
            int small = limit.preset(Profile.SMALL);
            int standard = limit.preset(Profile.STANDARD);
            int large = limit.preset(Profile.LARGE);
            assertTrue(limit.floor() <= small && small <= standard && standard <= large, limit.key());
        }
    }

    @Test
    void poolCoreNeverExceedsPoolMaxInAnyPreset() {
        for (Profile profile : Profile.values()) {
            for (Limit limit : Limit.values()) {
                if (!limit.name().endsWith("_CORE_THREADS")) {
                    continue;
                }
                Limit max = Limit.valueOf(limit.name().replace("_CORE_", "_MAX_"));
                assertTrue(limit.preset(profile) <= max.preset(profile), limit.key() + " " + profile);
            }
        }
    }

    @Test
    void theTuningGuideDocumentsEveryLimit() throws Exception {
        Path doc = Path.of("..", "docs", "capacity-tuning.md");
        Assumptions.assumeTrue(Files.exists(doc));
        String text = Files.readString(doc);
        for (Limit limit : Limit.values()) {
            assertTrue(text.contains("`" + limit.key() + "`"), limit.key() + " is missing from capacity-tuning.md");
        }
    }
}
