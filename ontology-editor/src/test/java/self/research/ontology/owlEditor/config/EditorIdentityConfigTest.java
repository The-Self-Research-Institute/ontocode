package self.research.ontology.owlEditor.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EditorIdentityConfigTest {

    @AfterEach
    void reset() {
        JwtIdentityExtractor.requireSignature(null);
        JwtIdentityExtractor.trustDesktopLaunchKey(null);
    }

    private static EditorIdentityConfig desktopConfig(String launchKey) {
        EditorIdentityConfig config = new EditorIdentityConfig(new MockEnvironment());
        ReflectionTestUtils.setField(config, "desktopMode", true);
        ReflectionTestUtils.setField(config, "desktopLaunchKey", launchKey);
        return config;
    }

    private static MockHttpServletRequest withLaunchKey(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(JwtIdentityExtractor.DESKTOP_LAUNCH_KEY_HEADER, key);
        return request;
    }

    @Test
    void desktopModeTrustsTheLaunchKeyTheAppPassedIn() {
        desktopConfig("key-from-electron").configure();

        assertEquals(Optional.of(JwtIdentityExtractor.DESKTOP_USER),
                JwtIdentityExtractor.extractEmail(withLaunchKey("key-from-electron")));
        assertFalse(JwtIdentityExtractor.signatureRequired());
    }

    @Test
    void desktopModeWithoutALaunchKeyLeavesTheAssistantSignedOut() {
        desktopConfig("").configure();

        assertEquals(Optional.empty(), JwtIdentityExtractor.extractEmail(withLaunchKey("")));
        assertEquals(Optional.empty(), JwtIdentityExtractor.extractEmail(withLaunchKey("anything")));
    }

    @Test
    void outsideDesktopModeALaunchKeyIsNeverTrusted() {
        EditorIdentityConfig config = new EditorIdentityConfig(new MockEnvironment());
        ReflectionTestUtils.setField(config, "desktopMode", false);
        ReflectionTestUtils.setField(config, "desktopLaunchKey", "key-from-electron");
        ReflectionTestUtils.setField(config, "jwtSecret", "");
        config.configure();

        assertEquals(Optional.empty(), JwtIdentityExtractor.extractEmail(withLaunchKey("key-from-electron")));
    }
}
