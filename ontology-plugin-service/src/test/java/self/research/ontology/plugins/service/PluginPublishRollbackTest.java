package self.research.ontology.plugins.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import self.research.ontology.plugins.dto.PublishPluginRequest;
import self.research.ontology.plugins.model.Plugin;
import self.research.ontology.plugins.model.PluginVersion;
import self.research.ontology.plugins.repository.PluginRepository;
import self.research.ontology.plugins.repository.PluginVersionRepository;
import self.research.ontology.plugins.storage.PluginStorageService;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

class PluginPublishRollbackTest {

    private PluginRepository plugins;
    private PluginVersionRepository versions;
    private PluginStorageService storage;
    private PluginService service;
    private PublishPluginRequest request;
    private final MockMultipartFile bundle = new MockMultipartFile("vsixFile", "p.js", "application/javascript", new byte[]{1});

    @BeforeEach
    void setUp() {
        plugins = mock(PluginRepository.class);
        versions = mock(PluginVersionRepository.class);
        storage = mock(PluginStorageService.class);
        service = new PluginService(plugins, versions, storage);
        request = new PublishPluginRequest();
        request.setPluginId("p1");
        request.setName("New name");
        request.setVersion("2.0.0");
        when(storage.uploadPlugin(any(), any(), any(), any())).thenReturn("file-new");
    }

    @Test
    void aFailedVersionSaveRemovesTheUploadedFileAndTheNewPlugin() {
        Plugin saved = Plugin.builder().id("pid").pluginId("p1").build();
        when(plugins.findByPluginId("p1")).thenReturn(Optional.empty()).thenReturn(Optional.empty()).thenReturn(Optional.of(saved));
        when(versions.findByPluginIdAndVersion("p1", "2.0.0")).thenReturn(Optional.empty());
        doThrow(new RuntimeException("mongo down")).when(versions).save(any());

        assertThrows(PluginPublishException.class, () -> service.publishPlugin(request, bundle, "a@x.org", false));

        verify(storage).deletePlugin("file-new");
        verify(plugins).delete(saved);
    }

    @Test
    void aFailedPluginSaveForAnExistingPluginRestoresTheEarlierVersionRecord() {
        Plugin existing = Plugin.builder().id("pid").pluginId("p1").name("Old name").authorEmail("a@x.org").build();
        PluginVersion oldVersion = PluginVersion.builder().id("vid").pluginId("p1").version("2.0.0").vsixFileId("file-old").build();
        when(plugins.findByPluginId("p1")).thenReturn(Optional.of(existing));
        when(versions.findByPluginIdAndVersion("p1", "2.0.0")).thenReturn(Optional.of(oldVersion));
        when(plugins.save(any())).thenReturn(existing);
        doThrow(new RuntimeException("mongo down")).when(versions).save(argThat(v -> "file-new".equals(v.getVsixFileId())));

        assertThrows(PluginPublishException.class, () -> service.publishPlugin(request, bundle, "a@x.org", false));

        verify(plugins).save(argThat(p -> "Old name".equals(p.getName())));
        verify(storage).deletePlugin("file-new");
        verify(versions, never()).delete(any());
    }
}
