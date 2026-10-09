package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import self.research.ontology.owlEditor.service.AssistantPluginInstallChecker.InstallStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AssistantPluginInstallCheckerTest {

    private static final String URL = "http://plugins:8087/api/plugins/swrl-editor-plugin/is-installed";

    private AssistantPluginInstallChecker checker;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        checker = new AssistantPluginInstallChecker();
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        ReflectionTestUtils.setField(checker, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(checker, "pluginServiceUrl", "http://plugins:8087");
        ReflectionTestUtils.setField(checker, "desktopMode", false);
    }

    @Test
    void reportsInstalledWhenThePluginServiceAnswersWithItsRealFieldName() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer t"))
                .andRespond(withSuccess("{\"isInstalled\":true}", MediaType.APPLICATION_JSON));

        assertEquals(InstallStatus.INSTALLED, checker.checkInstalled("swrl-editor-plugin", "Bearer t"));
    }

    @Test
    void stillAcceptsTheOlderFieldName() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"installed\":true}", MediaType.APPLICATION_JSON));

        assertEquals(InstallStatus.INSTALLED, checker.checkInstalled("swrl-editor-plugin", null));
    }

    @Test
    void reportsNotInstalledWhenTheUserHasNotInstalledIt() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"isInstalled\":false}", MediaType.APPLICATION_JSON));

        assertEquals(InstallStatus.NOT_INSTALLED, checker.checkInstalled("swrl-editor-plugin", null));
    }

    @Test
    void reportsNotAuthorizedWhenThePluginServiceRejectsTheLogin() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertEquals(InstallStatus.NOT_AUTHORIZED, checker.checkInstalled("swrl-editor-plugin", "Bearer t"));

        server.reset();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertEquals(InstallStatus.NOT_AUTHORIZED, checker.checkInstalled("swrl-editor-plugin", "Bearer t"));
    }

    @Test
    void reportsCheckFailedWhenThePluginServiceItselfFails() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertEquals(InstallStatus.CHECK_FAILED, checker.checkInstalled("swrl-editor-plugin", null));
    }

    @Test
    void reportsCheckFailedWhenThePluginServiceCannotBeReached() {
        server.expect(requestTo(URL)).andRespond(request -> {
            throw new java.net.ConnectException("connection refused");
        });

        assertEquals(InstallStatus.CHECK_FAILED, checker.checkInstalled("swrl-editor-plugin", null));
    }

    @Test
    void desktopAlwaysCountsAsInstalledWithoutCallingAnyone() {
        ReflectionTestUtils.setField(checker, "desktopMode", true);

        assertEquals(InstallStatus.INSTALLED, checker.checkInstalled("swrl-editor-plugin", null));
        server.verify();
    }
}
