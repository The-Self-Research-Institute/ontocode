package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class DesktopSaveBeansWiringTest {

    @Test
    void springCanCreateTheDraftSaverJournalAndCompactor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("storageManager", mock(StorageManager.class));
            context.getBeanFactory().registerSingleton("projectOntologyCache", mock(ProjectOntologyCache.class));
            context.getBeanFactory().registerSingleton("desktopOntologyLoader", mock(DesktopOntologyLoader.class));
            context.register(DesktopEditJournal.class, DesktopDraftSaver.class, DesktopDraftCompactor.class);
            context.refresh();

            assertNotNull(context.getBean(DesktopEditJournal.class));
            assertNotNull(context.getBean(DesktopDraftSaver.class));
            assertNotNull(context.getBean(DesktopDraftCompactor.class));
        }
    }
}
