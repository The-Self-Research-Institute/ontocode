package self.research.ontology.plugins.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import self.research.ontology.plugins.dto.PublishPluginRequest;
import self.research.ontology.plugins.model.Plugin;
import self.research.ontology.plugins.repository.PluginRepository;
import self.research.ontology.plugins.repository.PluginVersionRepository;
import self.research.ontology.plugins.storage.PluginStorageService;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginPublishOwnershipTest {

    private PluginRepository plugins;
    private PluginStorageService storage;
    private PluginService service;
    private PublishPluginRequest request;
    private final MockMultipartFile bundle = new MockMultipartFile("vsixFile", "p.js", "application/javascript", new byte[]{1});

    @BeforeEach
    void setUp() {
        plugins = mock(PluginRepository.class);
        storage = mock(PluginStorageService.class);
        service = new PluginService(plugins, mock(PluginVersionRepository.class), storage);
        request = new PublishPluginRequest();
        request.setPluginId("reasoner-plugin");
        request.setName("Reasoner");
        request.setVersion("9.9.9");
        Plugin existing = Plugin.builder().pluginId("reasoner-plugin").authorEmail("owner@x.org").build();
        when(plugins.findByPluginId("reasoner-plugin")).thenReturn(Optional.of(existing));
    }

    @Test
    void anotherUserCannotTakeOverAnExistingPlugin() {
        assertThrows(PluginOwnershipException.class, () -> service.publishPlugin(request, bundle, "mallory@x.org", false));

        verify(storage, never()).uploadPlugin(any(), any(), any(), any());
        verify(plugins, never()).save(any());
    }

    @Test
    void theOwnerCanPublishANewVersion() {
        when(storage.uploadPlugin(any(), any(), any(), any())).thenReturn("file-1");

        service.publishPlugin(request, bundle, "Owner@x.org", false);

        verify(plugins).save(any());
    }

    @Test
    void anAdminCanPublishOverAnotherUsersPlugin() {
        when(storage.uploadPlugin(any(), any(), any(), any())).thenReturn("file-1");

        service.publishPlugin(request, bundle, "admin@x.org", true);

        verify(plugins).save(any());
    }
}
