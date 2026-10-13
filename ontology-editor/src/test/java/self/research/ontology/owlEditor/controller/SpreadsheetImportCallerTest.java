package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpreadsheetImportCallerTest {

    private static MockHttpServletRequest withToken(String payloadJson) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String token = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
            + enc.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)) + ".sig";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    @Test
    void theTokensUserWinsOverWhatTheBrowserClaims() {
        MockHttpServletRequest request = withToken("{\"userId\":\"u-ravi\",\"email\":\"ravi@x.com\"}");

        SpreadsheetImportCaller.Caller caller = SpreadsheetImportCaller.resolve(request, "u-priya", "priya@x.com");

        assertEquals("u-ravi", caller.userId());
        assertEquals("ravi@x.com", caller.username());
    }

    @Test
    void aTokenWithoutUserIdUsesItsEmail() {
        MockHttpServletRequest request = withToken("{\"email\":\"ravi@x.com\"}");

        SpreadsheetImportCaller.Caller caller = SpreadsheetImportCaller.resolve(request, "u-priya", "Priya");

        assertEquals("ravi@x.com", caller.userId());
        assertEquals("ravi@x.com", caller.username());
    }

    @Test
    void withoutATokenTheRequestValuesAreKeptForDesktop() {
        SpreadsheetImportCaller.Caller caller =
            SpreadsheetImportCaller.resolve(new MockHttpServletRequest(), "desktop-user-local", "Desktop User");

        assertEquals("desktop-user-local", caller.userId());
        assertEquals("Desktop User", caller.username());
    }
}
