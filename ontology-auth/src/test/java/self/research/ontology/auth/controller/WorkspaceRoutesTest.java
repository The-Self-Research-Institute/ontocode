package self.research.ontology.auth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceRoutesTest {

    private static final String BASE = "/api/workspaces";

    private static List<String> allRoutes() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> routes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("self.research.ontology.auth.controller")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String prefix = classMapping == null || classMapping.value().length == 0 ? "" : classMapping.value()[0];
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String path = mapping.value().length == 0 ? "" : mapping.value()[0];
                for (RequestMethod verb : mapping.method()) {
                    routes.add(verb + " " + prefix + path);
                }
            }
        }
        return routes;
    }

    @Test
    void workspaceRoutesAreExactlyTheFourteenExpectedOnes() throws Exception {
        Set<String> expected = new TreeSet<>(List.of(
                "GET " + BASE,
                "GET " + BASE + "/check",
                "POST " + BASE,
                "GET " + BASE + "/{workspaceId}/subscription",
                "PATCH " + BASE + "/{workspaceId}/subscription",
                "POST " + BASE + "/{workspaceId}/select",
                "GET " + BASE + "/{workspaceId}",
                "PATCH " + BASE + "/{workspaceId}",
                "DELETE " + BASE + "/{workspaceId}",
                "POST " + BASE + "/{workspaceId}/restore",
                "GET " + BASE + "/deleted",
                "POST " + BASE + "/{workspaceId}/leave",
                "DELETE " + BASE + "/{workspaceId}/members/{userId}",
                "PUT " + BASE + "/{workspaceId}/members/{userId}/role"));

        List<String> workspaceRoutes = allRoutes().stream()
                .filter(route -> route.substring(route.indexOf(' ') + 1).startsWith(BASE))
                .sorted()
                .toList();

        assertEquals(expected.size(), workspaceRoutes.size(), "duplicate or missing route: " + workspaceRoutes);
        assertEquals(expected, new TreeSet<>(workspaceRoutes));
    }

    @Test
    void noRouteIsMappedTwiceAcrossControllers() throws Exception {
        List<String> routes = allRoutes();
        assertTrue(routes.size() == new TreeSet<>(routes).size(), "duplicate routes: " + routes);
    }
}
