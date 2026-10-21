package self.research.ontology.auth.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtRefreshGraceTest {

    private static final long HOUR = 3_600_000L;

    private JwtUtil jwt;

    @BeforeEach
    void setUp() {
        jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "SECRET_KEY", Base64.getEncoder().encodeToString(new byte[48]));
    }

    private String tokenExpiredHoursAgo(long hours) {
        ReflectionTestUtils.setField(jwt, "EXPIRATION_TIME", -hours * HOUR);
        return jwt.generateToken(new User("a@x.org", "", AuthorityUtils.createAuthorityList("ROLE_USER")), "a@x.org", "u-1");
    }

    @Test
    void aTokenThatJustExpiredCanStillBeRefreshed() {
        assertEquals("a@x.org", jwt.extractEmailAllowExpired(tokenExpiredHoursAgo(1)));
    }

    @Test
    void aTokenThatExpiredLongAgoCanNoLongerBeRefreshed() {
        assertThrows(IllegalArgumentException.class, () -> jwt.extractEmailAllowExpired(tokenExpiredHoursAgo(24 * 30)));
    }

    @Test
    void aTokenWithABadSignatureIsRefused() {
        String forged = tokenExpiredHoursAgo(1) + "x";

        assertThrows(IllegalArgumentException.class, () -> jwt.extractEmailAllowExpired(forged));
    }
}
