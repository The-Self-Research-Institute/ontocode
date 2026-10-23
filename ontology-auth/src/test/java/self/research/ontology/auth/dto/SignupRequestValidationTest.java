package self.research.ontology.auth.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignupRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private AuthRequests.SignupRequest request(String username) {
        AuthRequests.SignupRequest request = new AuthRequests.SignupRequest();
        request.setUsername(username);
        request.setEmail("new@x.org");
        request.setPassword("Str0ng!pass");
        return request;
    }

    @Test
    void aUsernameThatLooksLikeAnEmailAddressIsRefused() {
        assertFalse(validator.validate(request("victim@corp.com")).isEmpty());
    }

    @Test
    void anOrdinaryUsernameIsAccepted() {
        assertTrue(validator.validate(request("john smith")).isEmpty());
        assertTrue(validator.validate(request("j.smith_01")).isEmpty());
    }
}
