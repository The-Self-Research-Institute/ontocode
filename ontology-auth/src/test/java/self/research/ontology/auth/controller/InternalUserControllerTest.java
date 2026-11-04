package self.research.ontology.auth.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.service.InternalTokenVerifier;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalUserControllerTest {

    private UserRepository users;
    private InternalUserController controller;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        controller = new InternalUserController(users, new InternalTokenVerifier("s3cret"));
        User user = new User();
        user.setUsername("alice");
        user.setEmail("alice@x.org");
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
    }

    @Test
    void withTheInternalTokenTheEmailIsReturned() {
        ResponseEntity<?> response = controller.emailOf("alice", "s3cret");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("alice@x.org", ((Map<?, ?>) response.getBody()).get("email"));
    }

    @Test
    void withoutTheTokenNothingIsLookedUp() {
        assertEquals(HttpStatus.FORBIDDEN, controller.emailOf("alice", null).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.emailOf("alice", "wrong").getStatusCode());
        verify(users, never()).findByUsername("alice");
    }

    @Test
    void anUnknownUserIsNotFound() {
        assertEquals(HttpStatus.NOT_FOUND, controller.emailOf("nobody", "s3cret").getStatusCode());
    }

    @Test
    void aBlankConfiguredTokenAcceptsNothing() {
        assertFalse(new InternalTokenVerifier("").matches(""));
        assertFalse(new InternalTokenVerifier("").matches(null));
        assertTrue(new InternalTokenVerifier("x").matches("x"));
    }
}
