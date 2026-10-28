package self.research.ontology.plugins.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.plugins.dto.PluginDTO;
import self.research.ontology.plugins.dto.RatingResponse;
import self.research.ontology.plugins.model.Plugin;
import self.research.ontology.plugins.model.PluginRating;
import self.research.ontology.plugins.repository.PluginRatingRepository;
import self.research.ontology.plugins.repository.PluginRepository;
import self.research.ontology.plugins.repository.PluginVersionRepository;
import self.research.ontology.plugins.storage.PluginStorageService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicPluginDataMaskingTest {

    @Test
    void thePublicRatingListDoesNotShowReviewersEmailAddresses() {
        PluginRatingRepository ratings = mock(PluginRatingRepository.class);
        PluginRating rating = new PluginRating();
        rating.setPluginId("p1");
        rating.setUserId("reviewer@x.org");
        rating.setUsername("reviewer@x.org");
        when(ratings.findByPluginId("p1")).thenReturn(List.of(rating));

        List<RatingResponse> list = new PluginRatingService(ratings, mock(PluginRepository.class)).getPluginRatings("p1");

        assertEquals("r***@x.org", list.get(0).getUsername());
        assertEquals("r***@x.org", list.get(0).getUserId());
    }

    @Test
    void thePublicPluginDetailsDoNotShowTheAuthorsEmailAddress() {
        PluginRepository plugins = mock(PluginRepository.class);
        Plugin plugin = Plugin.builder().pluginId("p1").author("owner@x.org").build();
        when(plugins.findByPluginId("p1")).thenReturn(Optional.of(plugin));

        PluginDTO details = new PluginService(plugins, mock(PluginVersionRepository.class), mock(PluginStorageService.class))
                .getPluginDetails("p1");

        assertEquals("o***@x.org", details.getAuthor());
    }
}
