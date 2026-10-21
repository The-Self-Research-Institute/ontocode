package self.research.ontology.auth.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import self.research.ontology.auth.config.SecurityConfig;
import self.research.ontology.auth.service.DesktopDownloadService;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class DesktopDownloadAdminOnlyTest {

    @Configuration
    @EnableMethodSecurity
    static class Wiring {
        @Bean
        DesktopDownloadController controller() {
            return new DesktopDownloadController();
        }
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static DesktopDownloadController controllerWithMethodSecurity() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(Wiring.class);
        context.getBeanFactory().registerSingleton("gridFsTemplate", mock(GridFsTemplate.class));
        context.getBeanFactory().registerSingleton("downloadService", mock(DesktopDownloadService.class));
        context.refresh();
        return context.getBean(DesktopDownloadController.class);
    }

    private static void signInWith(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "someone@x.org", null, AuthorityUtils.createAuthorityList(role)));
    }

    @Test
    void theProductionSecurityConfigTurnsMethodSecurityOn() {
        assertNotNull(AnnotationUtils.findAnnotation(SecurityConfig.class, EnableMethodSecurity.class),
                "@PreAuthorize does nothing unless method security is enabled");
    }

    @Test
    void anOrdinaryUserCannotReplaceTheInstaller() {
        signInWith("ROLE_USER");

        assertThrows(AccessDeniedException.class, () -> controllerWithMethodSecurity().upload("windows", "setup.exe", "9.9.9", null,
                new MockMultipartFile("file", "setup.exe", "application/octet-stream", new byte[]{1})));
    }

    @Test
    void anOrdinaryUserCannotDeleteTheInstaller() {
        signInWith("ROLE_USER");

        assertThrows(AccessDeniedException.class, () -> controllerWithMethodSecurity().delete("windows"));
    }
}
